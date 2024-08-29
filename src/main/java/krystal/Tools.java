package krystal;

import com.google.common.io.Resources;
import krystal.Skip.SkipTypes;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A bunch of tools that are not present elsewhere.
 */
@Log4j2
@UtilityClass
public class Tools {
	
	/**
	 * Concatenate {@link Stream} of objects ({@link String#valueOf(Object)}) with given delimiter.
	 */
	public <T> String concat(@NonNull CharSequence delimiter, Stream<T> stream) {
		return stream.filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(delimiter));
	}
	
	/**
	 * Decode given {@link String} into sequence of {@link Integer}s using range notation. I.e. {@code "1,2,3:6,10"} will become {@link Set} of {@code {1,2,3,4,5,6,10}} where {@code ","} is <b><i>groupsDelimiter</i></b> and {@code ":"} is
	 * <b><i>rangesDelimiter</i></b>.
	 */
	public Set<Integer> expandGroupsOfRangesToInts(@NonNull String groupsDelimiter, @NonNull String rangesDelimiter, String ranges) {
		if (ranges == null) return Set.of();
		return Stream.of(ranges.split(groupsDelimiter)).flatMap(r -> {
			String[] range = r.strip().split(rangesDelimiter);
			return IntStream.rangeClosed(Integer.parseInt(range[0].strip()), Integer.parseInt(range[range.length - 1].strip())).boxed();
		}).collect(Collectors.toSet());
	}
	
	/**
	 * Applies {@link #groupSequentialIntegers(Integer...)} and transforms result into {@link String} using <b><i>groupsDelimiter</i></b> and <b><i>rangesDelimiter</i></b> to create a shortened pattern.
	 *
	 * @see #expandGroupsOfRangesToInts(String, String, String)
	 */
	public String intsAsGroupsOfRanges(@NonNull String groupsDelimiter, @NonNull String rangesDelimiter, Integer... ints) {
		return groupSequentialIntegers(ints).stream().map(l -> {
			if (l.size() > 1) return l.getFirst() + rangesDelimiter + l.getLast();
			else return l.getFirst().toString();
		}).collect(Collectors.joining(groupsDelimiter));
	}
	
	/**
	 * Groups into {@link List}s provided {@link Integer}s creating sequences with difference of 1.
	 */
	public Set<List<Integer>> groupSequentialIntegers(Integer... ints) {
		if (ints == null) return Set.of();
		
		val sequence = Arrays.stream(ints).sorted().toList();
		val result = new LinkedHashSet<List<Integer>>();
		val currentGroup = new LinkedList<Integer>();
		val prev = new AtomicInteger(sequence.getFirst());
		currentGroup.add(prev.get());
		
		sequence.forEach(i -> {
			if (i == prev.get()) return;
			
			if (Math.abs(i - prev.get()) == 1) {
				currentGroup.add(i);
			} else {
				result.add(List.copyOf(currentGroup));
				currentGroup.clear();
				currentGroup.add(i);
			}
			prev.set(i);
		});
		
		result.add(List.copyOf(currentGroup));
		
		return result;
	}
	
	/**
	 * Use this method to ensure that parts of the paths are concatenated using standard slash.
	 */
	public String concatAsURIPath(String... str) {
		return Stream.of(str).flatMap(s -> Stream.of(s.split("[\\\\/]"))).filter(s -> !s.isEmpty()).collect(Collectors.joining("/"));
	}
	
	/**
	 * Attempts to find resource directly (absolute or relative) and then by {@link Resources#getResource(String)}.
	 */
	public URL loadResource(String resource) {
		val path = Path.of(resource);
		log.trace("  > Loading resource: " + path);
		if (Files.exists(path)) {
			try {
				log.trace("    Resource found directly.");
				return path.toAbsolutePath().toUri().toURL();
			} catch (Exception ex) {
				log.fatal("  ! Exception while creating URL: " + ex.getMessage());
			}
		}
		
		log.trace("    Attempting to load from internal assets.");
		try {
			return Resources.getResource(resource);
		} catch (Exception ex) {
			log.fatal("  ! Exception while loading asset: " + ex.getMessage());
			return null;
		}
	}
	
	/**
	 * Conveniently links paths to form URI string and then loads resource.
	 *
	 * @see #loadResource(String)
	 */
	public Optional<URL> getResource(String... path) {
		return Optional.ofNullable(loadResource(concatAsURIPath(path)));
	}
	
	/**
	 * Retrieve the values of {@code returnType} for {@code annotation} annotated fields or methods within {@code invokedClass} of {@code invokedOn} object.
	 *
	 * @param invokedOn
	 * 		acceptable {@code null} only in case of the annotated static method or field. Will throw {@link NullPointerException} otherwise.
	 */
	@SuppressWarnings("unchecked")
	public <I, T, A extends Annotation> List<T> getAnnotatedValues(Class<A> annotation, Class<T> returnType, Class<? extends I> invokedClass, @Nullable I invokedOn) {
		
		return Stream.concat(
				Stream.of(invokedClass.getDeclaredFields())
				      .filter(f -> f.isAnnotationPresent(annotation) && returnType.isAssignableFrom(f.getType()) && f.trySetAccessible())
				      .map(f -> {
					      try {
						      return (T) f.get(invokedOn);
					      } catch (IllegalAccessException e) {
						      throw new RuntimeException(e);
					      }
				      }),
				Stream.of(invokedClass.getDeclaredMethods())
				      .filter(m -> m.isAnnotationPresent(annotation) && returnType.isAssignableFrom(m.getReturnType()) && m.trySetAccessible())
				      .map(m -> {
					      try {
						      return (T) m.invoke(invokedOn);
					      } catch (IllegalAccessException | InvocationTargetException e) {
						      throw new RuntimeException(e);
					      }
				      })
		).toList();
	}
	
	/**
	 * @see #getAnnotatedValues(Class, Class, Class, Object)
	 */
	public <I, T, A extends Annotation> T getFirstAnnotatedValue(Class<A> annotation, Class<T> returnType, Class<? extends I> invokedClass, @Nullable I invokedOn) {
		try {
			return getAnnotatedValues(annotation, returnType, invokedClass, invokedOn).getFirst();
		} catch (NoSuchElementException e) {
			return null;
		}
	}
	
	/**
	 * @see #getAnnotatedValues(Class, Class, Class, Object)
	 */
	public <I, T, A extends Annotation> T getFirstAnnotatedValue(Class<A> annotation, Class<T> returnType, I invokedOn) {
		return getFirstAnnotatedValue(annotation, returnType, invokedOn.getClass(), invokedOn);
	}
	
	/**
	 * Invoke annotated void methods for given object.
	 */
	public <A extends Annotation, I> void runAnnotatedMethods(Class<A> annotation, I invokedOn) {
		Stream.of(invokedOn.getClass().getDeclaredMethods())
		      .filter(m -> m.trySetAccessible()
				                   && m.isAnnotationPresent(annotation)
				                   && m.getReturnType().equals(void.class))
		      .forEach(m -> {
			      try {
				      m.invoke(invokedOn);
			      } catch (IllegalAccessException _) {
			      } catch (InvocationTargetException e) {
				      throw new RuntimeException("%s annotated method throws exception: %s.".formatted(m.getName(), e.getMessage()), e);
			      }
		      });
	}
	
	/**
	 * Splits given class type into raw class and type arguments.
	 *
	 * @return Tuple of Class and populated Type[] if any, empty otherwise. I.e. {@code List<Map<String,Set>>} returns {@code Pair<List, Map<String,Set>>}.
	 * @implNote Reflection methods to return {@link ParameterizedType}: {@link Method#getGenericReturnType()}, {@link Field#getGenericType()}.
	 */
	public TypesPair determineParameterTypes(Type elementType) {
		Class<?> elementClass = Objects.class;
		var arguments = new Type[0];
		
		if (elementType == null) return new TypesPair(elementClass, arguments);
		
		try {
			if (elementType instanceof ParameterizedType t) {
				elementClass = Class.forName(t.getRawType().getTypeName());
				arguments = t.getActualTypeArguments();
			} else {
				elementClass = Class.forName(elementType.getTypeName());
			}
			
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
		return new TypesPair(elementClass, arguments);
	}
	
	/**
	 * @see #determineParameterTypes(Type)
	 */
	public record TypesPair(Class<?> clazz, Type[] types) {
	
	}
	
	/**
	 * Checks if the {@link Field} is annotated with {@link Skip @Skip}. If so, evaluates if it should be skipped based on assigned {@link SkipTypes}.
	 * If {@link SkipTypes} are not specified, just returns {@code true} if the annotation is present.
	 *
	 * @see Skip
	 */
	public boolean isSkipped(Field field, SkipTypes... typesForField) {
		val annotation = field.getDeclaredAnnotation(Skip.class);
		if (annotation == null) return false;
		
		val types = List.of(typesForField);
		val explicitly = annotation.onlyThese();
		val excludes = annotation.everythingElseBut();
		
		if (types.isEmpty() || (explicitly.length == 0 && excludes.length == 0))
			return true;
		
		for (var type : explicitly)
			if (types.contains(type)) return true;
		
		if (excludes.length == 0) return false; // "onlyThese" passed
		
		for (var type : excludes)
			if (types.contains(type)) return false;
		
		return true; // "everythingElseBut" passed
	}
	
	/**
	 * When checking the String with regex pattern, the special regex characters must be escaped.
	 */
	public String escapeRegexSpecials(String fromString) {
		val regexSpecials = "[?^$|.+*\\\\]";
		return Arrays.stream(fromString.splitWithDelimiters(regexSpecials, 0))
		             .map(sub -> sub.matches(regexSpecials) ? "\\" + sub : sub)
		             .collect(Collectors.joining());
	}
	
}