package krystal;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.stream.Stream;

/**
 * Each pipeline execution is a single VirtualThread. The execution starts at first pipeline step declared, unless it is created with {@link #as(String)} method.
 *
 * @apiNote Inactive VP can be started with {@link #start()}.
 */
@Log4j2
@AllArgsConstructor
public class VirtualPromise<T> {
	
	private static final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
	private static @Setter int threadSleepDuration = 10;
	
	private final LinkedBlockingQueue<Thread> threads;
	private final AtomicReference<T> objectState;
	private final AtomicInteger stepsCount;
	private final AtomicReference<Thread> activeWorker;
	private final AtomicReference<Thread> queueWatcher;
	private final AtomicReference<Throwable> exception;
	private final AtomicReference<ExceptionsHandler> exceptionsHandler;
	/**
	 * You can put the further execution of the pipeline into hold. The {@link #activeWorker} thread will finish its tasks but won't trigger the next step in line.
	 *
	 * @see #setOnHold()
	 * @see #holdAndGet(Consumer)
	 * @see #start()
	 * @see #resume()
	 * @see #resumeNext()
	 * @see #cancel()
	 */
	private final AtomicBoolean holdState;
	/**
	 * The pipeline name used for debugging.
	 *
	 * @see #getActiveVirtualName()
	 */
	private final AtomicReference<String> pipelineName;
	private final AtomicReference<Thread> timeout;
	
	private VirtualPromise() {
		threads = new LinkedBlockingQueue<>();
		objectState = new AtomicReference<>();
		stepsCount = new AtomicInteger();
		activeWorker = new AtomicReference<>();
		queueWatcher = new AtomicReference<>();
		exception = new AtomicReference<>();
		exceptionsHandler = new AtomicReference<>();
		holdState = new AtomicBoolean(false);
		pipelineName = new AtomicReference<>("Unnamed VirtualPromise");
		timeout = null;
	}
	
	private VirtualPromise(String pipelineName) {
		this();
		this.pipelineName.set(pipelineName);
	}
	
	private VirtualPromise(Runnable runnable, String threadName) {
		this();
		stepsCount.getAndIncrement();
		Thread.startVirtualThread(() -> {
			activeWorker.set(Thread.currentThread());
			try {
				runnable.run();
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		}).setName(constructName(threadName, "run"));
	}
	
	private VirtualPromise(Supplier<T> supplier, String threadName) {
		this();
		stepsCount.getAndIncrement();
		Thread.startVirtualThread(() -> {
			activeWorker.set(Thread.currentThread());
			try {
				objectState.set(supplier.get());
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		}).setName(constructName(threadName, "supply"));
	}
	
	private VirtualPromise(String threadName, VirtualPromise<?>... promises) {
		this();
		stepsCount.getAndIncrement();
		Thread.startVirtualThread(() -> {
			activeWorker.set(Thread.currentThread());
			try {
				Stream.of(promises)
				      .map(p -> {
					      p.join();
					      return p.getException();
				      })
				      .filter(Objects::nonNull)
				      .findAny()
				      .ifPresent(exception::set);
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		}).setName(constructName(threadName, "fork"));
	}
	
	/*
	 * Factory
	 */
	
	/**
	 * Return blank, inactive VirtualPromise with a given name.
	 */
	public static VirtualPromise<Void> as(String pipelineName) {
		return new VirtualPromise<>(pipelineName);
	}
	
	public static VirtualPromise<Void> run(Runnable runnable) {
		return run(runnable, null);
	}
	
	public static VirtualPromise<Void> run(Runnable runnable, @Nullable String threadName) {
		return new VirtualPromise<>(runnable, threadName);
	}
	
	public static <T> VirtualPromise<T> supply(Supplier<T> supplier) {
		return supply(supplier, null);
	}
	
	public static <T> VirtualPromise<T> supply(Supplier<T> supplier, @Nullable String threadName) {
		return new VirtualPromise<>(supplier, threadName);
	}
	
	public static VirtualPromise<Void> fork(VirtualPromise<?>... promises) {
		return fork(null, promises);
	}
	
	public static VirtualPromise<Void> fork(@Nullable String threadName, VirtualPromise<?>... promises) {
		return new VirtualPromise<>(threadName, promises);
	}
	
	/*
	 * Intermediate methods
	 */
	
	public VirtualPromise<Void> thenRun(Runnable runnable) {
		return thenRun(runnable, null);
	}
	
	public VirtualPromise<Void> thenRun(Runnable runnable, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "thenRun")).unstarted(() -> {
			try {
				if (exception.get() == null) runnable.run();
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> thenSupply(Supplier<R> supplier) {
		return thenSupply(supplier, null);
	}
	
	public <R> VirtualPromise<R> thenSupply(Supplier<R> supplier, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "thenSupply")).unstarted(() -> {
			try {
				if (exception.get() == null) newState.set(supplier.get());
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> map(Function<T, R> function) {
		return map(function, null);
	}
	
	public <R> VirtualPromise<R> map(Function<T, R> function, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "map")).unstarted(() -> {
			try {
				if (exception.get() == null) newState.set(function.apply(objectState.get()));
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public VirtualPromise<T> apply(UnaryOperator<T> updater) {
		return apply(updater, null);
	}
	
	public VirtualPromise<T> apply(UnaryOperator<T> updater, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "apply")).unstarted(() -> {
			try {
				if (exception.get() == null) objectState.getAndUpdate(updater);
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return this;
	}
	
	public VirtualPromise<Void> accept(Consumer<T> consumer) {
		return accept(consumer, null);
	}
	
	public VirtualPromise<Void> accept(Consumer<T> consumer, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "accept")).unstarted(() -> {
			try {
				if (exception.get() == null) consumer.accept(objectState.get());
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> compose(Function<T, @NonNull VirtualPromise<R>> function) {
		return compose(function, null);
	}
	
	public <R> VirtualPromise<R> compose(Function<T, @NonNull VirtualPromise<R>> function, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "compose")).unstarted(() -> {
			try {
				val otherState = function.apply(objectState.get()).catchRun(this::setException).join();
				if (exception.get() == null) newState.set(otherState.orElse(null));
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> compose(Supplier<@NonNull VirtualPromise<R>> joiner) {
		return compose(joiner, null);
	}
	
	public <R> VirtualPromise<R> compose(Supplier<@NonNull VirtualPromise<R>> joiner, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "compose")).unstarted(() -> {
			try {
				val otherState = joiner.get().catchRun(this::setException).join();
				if (exception.get() == null) newState.set(otherState.orElse(null));
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <R, O> VirtualPromise<R> compose(VirtualPromise<O> otherPromise, BiFunction<O, T, R> combiner) {
		return compose(otherPromise, combiner, null);
	}
	
	/**
	 * The other promise exception affects the current pipeline. Effectively joins provided promise.
	 */
	public <R, O> VirtualPromise<R> compose(VirtualPromise<O> otherPromise, BiFunction<O, T, R> combiner, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "compose")).unstarted(() -> {
			try {
				val otherState = otherPromise.catchRun(this::setException).join();
				if (exception.get() == null) newState.set(combiner.apply(otherState.orElse(null), objectState.get()));
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <O, R> VirtualPromise<R> composeFlat(VirtualPromise<O> otherPromise, BiFunction<O, T, VirtualPromise<R>> returnedPromise) {
		return composeFlat(otherPromise, returnedPromise, null);
	}
	
	public <O, R> VirtualPromise<R> composeFlat(VirtualPromise<O> otherPromise, BiFunction<O, T, VirtualPromise<R>> returnedPromise, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "compose")).unstarted(() -> {
			try {
				val otherState = otherPromise.catchRun(this::setException).join();
				if (exception.get() == null) {
					newState.set(returnedPromise.apply(otherState.orElse(null), objectState.get()).catchRun(this::setException).join().orElse(null));
				}
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	/**
	 * @see #thenFork(String, VirtualPromise[])
	 */
	public VirtualPromise<Void> thenFork(VirtualPromise<?>... promises) {
		return thenFork(null, promises);
	}
	
	/**
	 * The provided promises will execute parallel to ongoing pipeline, and begin as soon as it states this step. To fork promises <b><i>after</i></b> finishing the previous step use {@link #thenFork(Supplier, String)}.
	 */
	public VirtualPromise<Void> thenFork(@Nullable String threadName, VirtualPromise<?>... promises) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "join")).unstarted(() -> {
			try {
				Stream.of(promises)
				      .map(p -> {
					      p.join();
					      return p.getException();
				      })
				      .filter(Objects::nonNull)
				      .findAny()
				      .ifPresent(exception::set);
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	/**
	 * @see #thenFork(Supplier, String)
	 */
	public VirtualPromise<Void> thenFork(Supplier<Stream<VirtualPromise<?>>> promises) {
		return thenFork(promises, null);
	}
	
	/**
	 * Joins provided fork of promises, which will begin executions <b><i>after</i></b> finishing the previous step.
	 */
	public VirtualPromise<Void> thenFork(Supplier<Stream<VirtualPromise<?>>> promises, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "join")).unstarted(() -> {
			try {
				promises.get()
				        .map(p -> {
					        p.join();
					        return p.getException();
				        })
				        .filter(Objects::nonNull)
				        .findAny()
				        .ifPresent(exception::set);
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <E, R> VirtualPromise<Stream<R>> mapFork(Function<T, Stream<E>> streamSupplier, Function<E, R> elementMapper) {
		return mapFork(streamSupplier, elementMapper, null);
	}
	
	/**
	 * @param elementMapper
	 * 		Throws {@link NullPointerException} if the return of this {@link Function} is {@code null} or of {@link Void} type.
	 */
	public <E, R> VirtualPromise<Stream<R>> mapFork(Function<T, Stream<E>> streamSupplier, Function<E, R> elementMapper, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val newState = new AtomicReference<Stream<R>>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "mapFork")).unstarted(() -> {
			try {
				if (exception.get() == null) {
					val object = objectState.get();
					val elementsCount = streamSupplier.apply(object).mapToInt(e -> 1).sum();
					val result = new ConcurrentHashMap<Integer, R>(elementsCount);
					val counter = new AtomicInteger();
					
					// split to the fork
					streamSupplier.apply(object).forEach(
							o -> {
								val i = counter.getAndIncrement();
								Thread.ofVirtual().start(() -> {
									try {
										// elementMapper can not return null or will throw
										if (exception.get() == null) result.put(i, elementMapper.apply(o));
									} catch (Exception e) {
										setException(e);
									}
								});
							});
					
					// monitor the fork done
					while (result.size() != elementsCount && exception.get() == null) {
						Thread.sleep(threadSleepDuration);
					}
					
					if (exception.get() == null)
						newState.set(result.entrySet()
						                   .stream()
						                   .sorted(Comparator.comparingInt(Entry::getKey))
						                   .map(Entry::getValue));
				}
			} catch (Exception e) {
				exception.compareAndSet(null, e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<Void> acceptFork(Function<T, Stream<R>> streamSupplier, Consumer<R> elementConsumer) {
		return acceptFork(streamSupplier, elementConsumer, null);
	}
	
	public <R> VirtualPromise<Void> acceptFork(Function<T, Stream<R>> streamSupplier, Consumer<R> elementConsumer, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "acceptFork")).unstarted(() -> {
			try {
				if (exception.get() == null) {
					val object = objectState.get();
					val elementsCount = streamSupplier.apply(object).mapToInt(e -> 1).sum();
					val counter = new AtomicInteger();
					
					// split to the fork
					streamSupplier.apply(object).forEach(
							o -> Thread.ofVirtual().start(() -> {
								try {
									if (exception.get() == null) {
										elementConsumer.accept(o);
										counter.getAndIncrement();
									}
								} catch (Exception e) {
									setException(e);
									activeWorker.get().interrupt();
								}
							}));
					
					// monitor the fork done
					while (counter.get() != elementsCount) {
						Thread.sleep(threadSleepDuration);
					}
					
				}
			} catch (Exception e) {
				exception.compareAndSet(null, e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	/*
	 * Error handling
	 */
	
	/**
	 * @see #catchRun(Consumer, String)
	 */
	public VirtualPromise<T> catchRun(Consumer<Throwable> consumer) {
		return catchRun(consumer, null);
	}
	
	/**
	 * Resolve any exception, registered up to the current step in the pipeline. Occurrence of the exception prevents the executions down the pipeline, until it is resolved. This step in the pipeline can throw exception, thus overwriting the one being the
	 * subject.
	 *
	 * @see #catchSupply(Function, String)
	 * @see #catchThrow(String)
	 * @see #catchExceptions(ExceptionsHandler)
	 */
	public VirtualPromise<T> catchRun(Consumer<Throwable> consumer, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "catchRun")).unstarted(() -> {
			try {
				val ex = exception.get();
				if (ex != null) {
					consumer.accept(ex);
					exception.set(null);
				}
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return this;
	}
	
	/**
	 * @see #catchSupply(Function, String)
	 */
	public VirtualPromise<T> catchSupply(Function<Throwable, T> consumeAndSupplyFunction) {
		return catchSupply(consumeAndSupplyFunction, null);
	}
	
	/**
	 * Resolve like {@link #catchRun(Consumer)}, but instead consuming the exception, supply the pipeline.
	 */
	public VirtualPromise<T> catchSupply(Function<Throwable, T> consumeAndSupplyFunction, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "catchSupply")).unstarted(() -> {
			try {
				val ex = exception.get();
				if (ex != null) {
					objectState.set(consumeAndSupplyFunction.apply(ex));
					exception.set(null);
				}
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return this;
	}
	
	/**
	 * @see #catchThrow(String)
	 */
	public VirtualPromise<T> catchThrow() {
		return catchThrow(null);
	}
	
	/**
	 * Resolve held exception (if any) by throwing {@link RuntimeException}.
	 */
	public VirtualPromise<T> catchThrow(@Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "catchThrow")).unstarted(() -> {
			
			val ex = exception.get();
			if (ex != null) {
				throw new RuntimeException(ex);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return this;
	}
	
	/**
	 * Set a default exceptions handler as a step in pipeline, to be applied to the next steps.
	 * This will resolve exceptions at their occurrence rather than at the catching step like {@link #catchRun(Consumer, String)}.
	 *
	 * @param exceptionsHandler
	 * 		Set to {@code null} to trigger off.
	 * @see ExceptionsHandler
	 * @see #setExceptionsHandler(ExceptionsHandler)
	 */
	public VirtualPromise<T> catchExceptions(@Nullable ExceptionsHandler exceptionsHandler) {
		return monitor(_ -> this.exceptionsHandler.set(exceptionsHandler));
	}
	
	/**
	 * Create a default catchExceptions for the whole pipeline.
	 *
	 * @param overridePipelineHandlers
	 * 		If set to {@code true}, exceptions won't be stored within pipeline which won't trigger any further catching steps like {@link #catchRun(Consumer, String)}.
	 */
	public record ExceptionsHandler(boolean overridePipelineHandlers, Consumer<Throwable> exceptionsHandler) {
		
		public ExceptionsHandler(Consumer<Throwable> exceptionsHandler) {
			this(true, exceptionsHandler);
		}
		
	}
	
	/*
	 * Monitoring
	 */
	
	/**
	 * Take {@link Consumer} action on current {@link VirtualPromise}.
	 * I.e. this step can be used to create dependencies on other {@link VirtualPromise}, at the time of evaluation.
	 *
	 * @see #mirror(Supplier)
	 */
	public VirtualPromise<T> monitor(Consumer<VirtualPromise<T>> actionOnSelf, @Nullable String threadName) {
		stepsCount.getAndIncrement();
		val thread = Thread.ofVirtual().name(constructName(threadName, "monitor")).unstarted(() -> {
			try {
				if (exception.get() == null) actionOnSelf.accept(this);
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
		threads.offer(thread);
		return this;
	}
	
	/**
	 * @see #monitor(Consumer, String)
	 */
	public VirtualPromise<T> monitor(Consumer<VirtualPromise<T>> actionOnSelf) {
		return monitor(actionOnSelf, null);
	}
	
	/**
	 * Monitor and {@link #monitor(Consumer, String) mirror} other promises state, creating dependency to resume execution.
	 * If the other promises have exceptions or are {@link #isIdle() idle} - {@link #cancelAndDrop()}.
	 * If they are on {@link #holdState hold} - {@link Thread#sleep(long) wait} until they resume.
	 */
	public VirtualPromise<T> mirror(Supplier<Stream<VirtualPromise<?>>> others) {
		return monitor(_ -> others.get().forEach(promise -> {
			if (this.isIdle() || hasException()) return;
			
			if (promise.hasException() || promise.isIdle()) {
				cancelAndDrop();
				return;
			}
			while (promise.isOnHold()) {
				if (promise.hasException() || promise.isIdle()) {
					cancelAndDrop();
					return;
				}
				try {
					Thread.sleep(threadSleepDuration);
				} catch (Exception e) {
					setException(e);
				}
			}
		}));
	}
	
	/*
	 * Private tools
	 */
	
	private void arriveAndStartNextThread() {
		stepsCount.getAndDecrement();
		takeNextThread();
	}
	
	private void takeNextThread() {
		val thread = Thread.ofVirtual().name("%s queue watcher".formatted(pipelineName.get())).unstarted(() -> {
			activeWorker.set(null);
			if (!holdState.get()) {
				try {
					while (threads.isEmpty())
						Thread.sleep(threadSleepDuration); // possible pinning with take()?
					val next = threads.poll();
					activeWorker.set(next);
					next.start();
				} catch (InterruptedException e) {
					holdState.set(true);
				}
			}
			queueWatcher.set(null);
		});
		queueWatcher.set(thread);
		thread.start();
	}
	
	/**
	 * Sets the pipeline name for the <b><i>next</i></b> steps.
	 *
	 * @see #as(String)
	 */
	public VirtualPromise<T> name(String pipelineName) {
		this.pipelineName.set(pipelineName);
		return this;
	}
	
	/**
	 * Uses current {@link #objectState} as the source for the name.
	 *
	 * @see #name(String)
	 */
	public VirtualPromise<T> name(Function<T, String> withCurrentObject) {
		this.pipelineName.set(withCurrentObject.apply(objectState.get()));
		return this;
	}
	
	public String getName() {
		return pipelineName.get();
	}
	
	public void setException(Throwable exc) {
		if (!Optional.ofNullable(exceptionsHandler.get())
		             .map(h -> {
			             h.exceptionsHandler.accept(exc);
			             return h.overridePipelineHandlers;
		             })
		             .orElse(false)
		) exception.set(exc);
	}
	
	/**
	 * Set to {@link null} to disable.
	 *
	 * @see ExceptionsHandler
	 * @see #catchExceptions(ExceptionsHandler)
	 */
	public VirtualPromise<T> setExceptionsHandler(@Nullable ExceptionsHandler exceptionsHandler) {
		this.exceptionsHandler.set(exceptionsHandler);
		return this;
	}
	
	private String constructName(String threadName, String defaultName) {
		return "%s: [%s]".formatted(pipelineName.get(), threadName == null ? defaultName : threadName);
	}
	
	/*
	 * Handling
	 */
	
	/**
	 * Wait for the pipeline to complete and return the {@link Optional} of the result. If the promise is in the hold state, just returns the Optional with current state of object.
	 *
	 * @see #holdState
	 */
	public Optional<T> join() {
		// phaser.arriveAndAwaitAdvance(); AWAIT ADVANCE IS PINNING VIRTUAL THREADS :(
		if (!holdState.get()) {
			while (!isComplete() || isActive()) {
				if (isIdle() || hasException()) break;
				try {
					Thread.sleep(threadSleepDuration);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * Effectively {@link #catchThrow()} and {@link #join()}, wrapped together.
	 *
	 * @see #holdState
	 */
	public Optional<T> joinThrow() {
		return this.catchThrow().join();
	}
	
	public @Nullable Throwable getException() {
		return exception.get();
	}
	
	/**
	 * Join the active worker, hold further pipeline executions, and get the result.
	 *
	 * @see #holdState
	 * @see #join()
	 * @see #resume()
	 */
	public Optional<T> holdAndGet(@Nullable Consumer<InterruptedException> handler) {
		holdState.set(true);
		try {
			val thread = activeWorker.get();
			if (thread != null) thread.join();
		} catch (InterruptedException e) {
			if (handler != null)
				handler.accept(e);
		}
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * Interrupt and cancel the current execution and set the pipeline on hold state.
	 *
	 * @see #holdState
	 * @see #cancelAndDrop()
	 * @see #cancelAndGet()
	 */
	public void cancel() {
		holdState.set(true);
		Optional.ofNullable(activeWorker.getAndSet(null)).ifPresent(t -> {
			t.interrupt();
			activeWorker.set(null);
		});
		Optional.ofNullable(queueWatcher.getAndSet(null)).ifPresent(t -> {
			t.interrupt();
			queueWatcher.set(null);
		});
	}
	
	/**
	 * {@link #cancel()} and drop any further steps.
	 */
	public void cancelAndDrop() {
		cancel();
		threads.clear();
	}
	
	/**
	 * {@link #cancel()} and get the current result.
	 *
	 * @see #join()
	 */
	public Optional<T> cancelAndGet() {
		cancel();
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * True if all threads in pipeline completed their tasks, and it is ready to return result.
	 */
	public boolean isComplete() {
		return stepsCount.get() == 0;
	}
	
	/**
	 * True if there is any thread working at the moment.
	 */
	public boolean isActive() {
		return activeWorker.get() != null;
	}
	
	/**
	 * True if there is any thread working at the moment or any thread watching the queue.
	 */
	public boolean isAlive() {
		return activeWorker.get() != null || queueWatcher.get() != null;
	}
	
	/**
	 * Is on hold and has no further steps to follow. I.e. as a result of {@link #cancelAndDrop()}.
	 */
	public boolean isIdle() {
		return isOnHold() && threads.isEmpty();
	}
	
	/**
	 * @see #holdState
	 */
	public boolean isOnHold() {
		return holdState.get();
	}
	
	/**
	 * @see #holdState
	 */
	public VirtualPromise<T> setOnHold() {
		holdState.set(true);
		return this;
	}
	
	public boolean isDropped() {
		return isIdle() && stepsCount.get() > 0;
	}
	
	public boolean hasException() {
		return exception.get() != null;
	}
	
	/**
	 * Switch the {@link #holdState} flag to false. Does not invoke execution of queued threads. Can be used as {@link #mirror(Supplier)} for other VPs.
	 *
	 * @see #holdState
	 * @see #start()
	 */
	public VirtualPromise<T> resume() {
		holdState.set(false);
		return this;
	}
	
	/**
	 * If the pipeline is not {@link #isAlive() alive}, begin the executions by invoking the next thread in line. The pipeline can still be on {@link #holdState}, then this method won't trigger further execution.
	 *
	 * @see #holdState
	 * @see #resumeNext()
	 */
	public VirtualPromise<T> start() {
		if (!isAlive()) takeNextThread();
		return this;
	}
	
	/**
	 * {@link #start()} but switch the {@link #holdState} flag to false first.
	 */
	public VirtualPromise<T> resumeNext() {
		return resume().start();
	}
	
	/**
	 * Start a watcher thread, that after given timeout, will {@link #cancel()} the promise if not {@link #isComplete()}. The exception while waiting, does not affect the pipeline and can be handled independently.
	 */
	public VirtualPromise<T> setTimeout(Duration duration, @Nullable Consumer<InterruptedException> handler) {
		timeout.set(Thread.startVirtualThread(() -> {
			try {
				Thread.sleep(duration);
				if (!isComplete()) cancel();
			} catch (InterruptedException e) {
				if (handler != null)
					handler.accept(e);
			}
		}));
		return this;
	}
	
	/**
	 * Active thread name has a format of {@code "pipelineName: [threadName]"}, where, by default, it gets a name from corresponding pipeline task (i.e. "thenRun").
	 */
	public String getActiveVirtualName() {
		return activeWorker.get().getName();
	}
	
	public VirtualPromise<Void> toVoid() {
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	/*
	 * Virtual CompletableFuture Factory
	 */
	
	public static CompletableFuture<Void> futureRun(Runnable runnable) {
		return CompletableFuture.runAsync(runnable, virtualExecutor);
	}
	
	public static <F> CompletableFuture<F> futureSupply(Supplier<F> supplier) {
		return CompletableFuture.supplyAsync(supplier, virtualExecutor);
	}
	
	public static <T> VirtualPromise<T> fromFuture(CompletableFuture<T> future) {
		return VirtualPromise.supply(future::join);
	}
	
	public CompletableFuture<T> toFuture() {
		return futureSupply(() -> joinThrow().orElse(null));
	}
	
}