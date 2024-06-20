package krystal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Skip annotation used within Krystal packages. Use {@link Tools#isSkipped(Field, SkipTypes...)} for evaluation.
 * Can be used without specifying {@link SkipTypes}, so that the evaluation will return {@code true} if the annotation is present.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Skip {
	
	/**
	 * Skip only these {@link SkipTypes} listed.
	 *
	 * @apiNote Is evaluated before {@link #everythingElseBut()}.
	 */
	SkipTypes[] onlyThese() default {};
	
	/**
	 * Skip everything but listed.
	 *
	 * @apiNote Is evaluated after {@link #onlyThese()}.
	 */
	SkipTypes[] everythingElseBut() default {};
	
	/**
	 * Refers to various modules used in Krystal packages.
	 */
	enum SkipTypes {
		/**
		 * Marks additional fields in class, which should not be taken to persistence (are not fields found in database table). Not marked fields will cause exceptions when running persistence methods.
		 */
		persistence,
		/**
		 * Mark fields to be skipped in (de-)serializations in {@link JSON} tool.
		 */
		skipson,
		/**
		 * Mark fields which will be filtered-out for {@link StringRenderer#renderObjects(List)};
		 */
		render
	}
	
}