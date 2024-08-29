package krystal;

import krystal.Skip.SkipTypes;
import lombok.val;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Interface to check important fields values with predicate.
 */
public interface CheckImportantFieldsInterface {
	
	default boolean noneIsNull() {
		return importantFieldsValuesNoneMatch(Objects::isNull);
	}
	
	default boolean importantFieldsValuesAllMatch(Predicate<Object> predicate) {
		return getFieldsToCheck().allMatch(f -> {
			try {
				return predicate.test(f.get(this));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		});
	}
	
	default boolean importantFieldsValuesNoneMatch(Predicate<Object> predicate) {
		return getFieldsToCheck().noneMatch(f -> {
			try {
				return predicate.test(f.get(this));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		});
	}
	
	default boolean importantFieldsValuesAnyMatch(Predicate<Object> predicate) {
		return getFieldsToCheck().anyMatch(f -> {
			try {
				return predicate.test(f.get(this));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		});
	}
	
	private Stream<Field> getFieldsToCheck() {
		val clazz = getClass();
		val explicitCheck = clazz.isAnnotationPresent(ExplicitImportance.class);
		
		return Arrays.stream(clazz.getDeclaredFields())
		             .filter(f -> f.trySetAccessible()
				                          && explicitCheck
		                          ? f.isAnnotationPresent(ImportantField.class)
		                          : (!f.isAnnotationPresent(NotImportantField.class)
				                             && !Tools.isSkipped(f, SkipTypes.importance))
		             );
	}
	
	/**
	 * {@link Skip} with {@link SkipTypes#importance} can be used instead.
	 */
	@Target({ElementType.FIELD})
	@Retention(RetentionPolicy.RUNTIME)
	@interface NotImportantField {
	
	}
	
	@Target({ElementType.TYPE})
	@Retention(RetentionPolicy.RUNTIME)
	@interface ExplicitImportance {
	
	}
	
	@Target({ElementType.FIELD})
	@Retention(RetentionPolicy.RUNTIME)
	@interface ImportantField {
	
	}
	
}