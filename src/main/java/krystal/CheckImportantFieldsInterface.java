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
 * Interface to check important fields values with predicates. All fields are treated as important by default, thus are subjects for the predicate. Use annotations to fine tune the behaviour.
 *
 * @see ExplicitImportance
 * @see ImportantField
 * @see NotImportantField
 * @see Skip
 */
public interface CheckImportantFieldsInterface {
	
	/**
	 * Check if none of important fields are null. By default, all fields are important.
	 */
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
				                          && (explicitCheck
				                              ? f.isAnnotationPresent(ImportantField.class)
				                              : (!f.isAnnotationPresent(NotImportantField.class))
						                                && !Tools.isSkipped(f, SkipTypes.importance))
		             );
	}
	
	/**
	 * By default, all {@link Field Fields} are treated as important. With this annotation only {@link ImportantField} fields are selected.
	 */
	@Target({ElementType.TYPE})
	@Retention(RetentionPolicy.RUNTIME)
	@interface ExplicitImportance {
	
	}
	
	/**
	 * Use to exclude field from the predicate. {@link Skip} with {@link SkipTypes#importance} can be used instead.
	 */
	@Target({ElementType.FIELD})
	@Retention(RetentionPolicy.RUNTIME)
	@interface NotImportantField {
	
	}
	
	/**
	 * Used with {@link ExplicitImportance} to declare fields to check.
	 */
	@Target({ElementType.FIELD})
	@Retention(RetentionPolicy.RUNTIME)
	@interface ImportantField {
	
	}
	
}