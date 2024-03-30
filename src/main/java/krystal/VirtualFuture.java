package krystal;

import lombok.val;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

public class VirtualFuture<T> {
	
	private final LinkedBlockingQueue<Thread> threads;
	private final AtomicReference<T> state;
	private final Phaser phaser;
	private final AtomicReference<Thread> worker;
	private final List<Exception> exceptions;
	
	private VirtualFuture() {
		threads = new LinkedBlockingQueue<>();
		state = new AtomicReference<>();
		phaser = new Phaser(1);
		worker = new AtomicReference<>();
		exceptions = Collections.synchronizedList(new ArrayList<>());
	}
	
	VirtualFuture(LinkedBlockingQueue<Thread> threads, AtomicReference<T> state, Phaser phaser, AtomicReference<Thread> worker, List<Exception> exceptions) {
		this.threads = threads;
		this.state = state;
		this.phaser = phaser;
		this.worker = worker;
		this.exceptions = exceptions;
	}
	
	private VirtualFuture(Runnable runnable) {
		this();
		phaser.register();
		worker.set(Thread.startVirtualThread(() -> {
			try {
				runnable.run();
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
	}
	
	private VirtualFuture(Supplier<T> supplier) {
		this();
		phaser.register();
		worker.set(Thread.startVirtualThread(() -> {
			try {
				state.set(supplier.get());
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
	}
	
	public static VirtualFuture<Void> run(Runnable runnable) {
		return new VirtualFuture<>(runnable);
	}
	
	public VirtualFuture<Void> thenRun(Runnable runnable) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				runnable.run();
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, new AtomicReference<>(), phaser, worker, exceptions);
	}
	
	public static <T> VirtualFuture<T> supply(Supplier<T> supplier) {
		return new VirtualFuture<>(supplier);
	}
	
	public static <T> VirtualFuture<T> supply(T object) {
		return supply(() -> object);
	}
	
	public <R> VirtualFuture<R> thenSupply(Supplier<R> supplier) {
		phaser.register();
		val newState = new AtomicReference<R>();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				newState.set(supplier.get());
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, newState, phaser, worker, exceptions);
	}
	
	public <R> VirtualFuture<R> thenSupply(R object) {
		return thenSupply(() -> object);
	}
	
	// public VirtualFuture<Void> thenRunIf(BooleanSupplier test, Runnable runnable) {
	// 	futures.offer(pool.submit(() -> {
	// 		futures.take().get();
	// 		if (test.getAsBoolean())
	// 			return runnable;
	// 		return null;
	// 	}));
	// 	return new VirtualFuture<>(pool, futures);
	// }
	
	public <R> VirtualFuture<R> thenApply(Function<T, R> function) {
		phaser.register();
		val newState = new AtomicReference<R>();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				newState.set(function.apply(state.get()));
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, newState, phaser, worker, exceptions);
	}
	
	/*
	 * Accept methods
	 */
	
	public VirtualFuture<Void> thenAccept(Consumer<T> consumer) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				consumer.accept(state.get());
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, new AtomicReference<>(), phaser, worker, exceptions);
	}
	
	public VirtualFuture<T> thenCompose(Consumer<T> updater) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				state.getAndUpdate(o -> {
					updater.accept(o);
					return o;
				});
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return this;
	}
	
	public VirtualFuture<Void> thenAcceptIf(BooleanSupplier test, Consumer<T> consumer) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				consumeIfElse(state.get(), test, consumer, null);
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, new AtomicReference<>(), phaser, worker, exceptions);
	}
	
	public VirtualFuture<Void> thenAcceptIf(Predicate<T> test, Consumer<T> consumer) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				val obj = state.get();
				consumeIfElse(obj, () -> test.test(obj), consumer, null);
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, new AtomicReference<>(), phaser, worker, exceptions);
	}
	
	public VirtualFuture<T> thenComposeIf(BooleanSupplier test, Consumer<T> updater) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				if (test.getAsBoolean())
					state.getAndUpdate(o -> {
						updater.accept(o);
						return o;
					});
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return this;
	}
	
	public VirtualFuture<Void> thenAcceptIfElse(BooleanSupplier test, Consumer<T> consumerIf, Consumer<T> consumerElse) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				consumeIfElse(state.get(), test, consumerIf, consumerElse);
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return new VirtualFuture<>(threads, new AtomicReference<>(), phaser, worker, exceptions);
	}
	
	public VirtualFuture<T> thenComposeIfElse(BooleanSupplier test, Consumer<T> updaterIf, Consumer<T> updaterElse) {
		phaser.register();
		threads.offer(Thread.ofVirtual().unstarted(() -> {
			try {
				state.getAndUpdate(o -> {
					if (test.getAsBoolean()) {
						updaterIf.accept(o);
					} else {
						updaterElse.accept(o);
					}
					return o;
				});
				phaser.arrive();
				val next = threads.take();
				worker.set(next);
				next.start();
			} catch (Exception e) {
				if (phaser.getUnarrivedParties() > 0)
					exceptions.addLast(e);
			}
		}));
		return this;
	}
	
	private void consumeIfElse(T object, BooleanSupplier test, Consumer<T> consumerIf, Consumer<T> consumerElse) {
		if (test.getAsBoolean()) consumerIf.accept(object);
		else if (consumerElse != null) consumerElse.accept(object);
	}
	
	/**
	 * Get stored object
	 */
	public Optional<T> join() {
		phaser.arriveAndAwaitAdvance();
		if (!exceptions.isEmpty()) {
			exceptions.forEach(Throwable::printStackTrace);
			throw new RuntimeException("VirtualFuture ended with exceptions.");
		}
		worker.get().interrupt(); // last worker is waiting for next worker
		return Optional.ofNullable(state.get());
	}
	
	public boolean hasExceptions() {
		return !exceptions.isEmpty();
	}
	
	public Optional<T> drop() {
		try {
			worker.get().interrupt();
		} catch (Exception ignored) {
		}
		return Optional.ofNullable(state.get());
	}
	
}