package krystal;

import lombok.NoArgsConstructor;

import java.util.Optional;
import java.util.function.*;

@NoArgsConstructor
public class CompletablePresent<T> {
	
	private T object;
	
	private CompletablePresent(T object) {
		this.object = object;
	}
	
	public static CompletablePresent<Void> run(Runnable runnable) {
		runnable.run();
		return new CompletablePresent<>();
	}
	
	public static <T> CompletablePresent<T> supply(Supplier<T> supplier) {
		return new CompletablePresent<>(supplier.get());
	}
	
	public static <T> CompletablePresent<T> supply(T object) {
		return new CompletablePresent<>(object);
	}
	
	public CompletablePresent<Void> thenRun(Runnable runnable) {
		return run(runnable);
	}
	
	public CompletablePresent<Void> thenRunIf(BooleanSupplier test, Runnable runnable) {
		if (test.getAsBoolean())
			return run(runnable);
		else
			return new CompletablePresent<>();
	}
	
	public <R> CompletablePresent<R> thenApply(Function<T, R> function) {
		return new CompletablePresent<>(function.apply(object));
	}
	
	public <R> CompletablePresent<R> thenSupply(Supplier<R> supplier) {
		return supply(supplier);
	}
	
	public <R> CompletablePresent<R> thenSupply(R object) {
		return supply(object);
	}
	
	/*
	 * Accept methods
	 */
	
	public CompletablePresent<Void> thenAccept(Consumer<T> consumer) {
		consumer.accept(object);
		return new CompletablePresent<>();
	}
	
	public CompletablePresent<T> thenCompose(Consumer<T> consumer) {
		consumer.accept(object);
		return this;
	}
	
	public CompletablePresent<Void> thenAcceptIf(BooleanSupplier test, Consumer<T> consumer) {
		consumeIfElse(test, consumer, null);
		return new CompletablePresent<>();
	}
	
	public CompletablePresent<Void> thenAcceptIf(Predicate<T> test, Consumer<T> consumer) {
		consumeIfElse(() -> test.test(object), consumer, null);
		return new CompletablePresent<>();
	}
	
	public CompletablePresent<T> thenComposeIf(BooleanSupplier test, Consumer<T> consumer) {
		consumeIfElse(test, consumer, null);
		return this;
	}
	
	public CompletablePresent<Void> thenAcceptIfElse(BooleanSupplier test, Consumer<T> consumerIf, Consumer<T> consumerElse) {
		consumeIfElse(test, consumerIf, consumerElse);
		return new CompletablePresent<>();
	}
	
	public CompletablePresent<T> thenComposeIfElse(BooleanSupplier test, Consumer<T> consumerIf, Consumer<T> consumerElse) {
		consumeIfElse(test, consumerIf, consumerElse);
		return this;
	}
	
	private void consumeIfElse(BooleanSupplier test, Consumer<T> consumerIf, Consumer<T> consumerElse) {
		if (test.getAsBoolean()) consumerIf.accept(object);
		else if (consumerElse != null) consumerElse.accept(object);
	}
	
	/**
	 * Get stored object
	 */
	public Optional<T> getResult() {
		return Optional.ofNullable(object);
	}
	
}
