package krystal;

import com.google.common.io.Resources;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
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
	
	@SuppressWarnings("unchecked")
	public <T, A extends Annotation> List<T> getAnnotadedValues(Class<A> annotationClass, Class<T> returnType, Object invokedOn) {
		
		return Stream.concat(
				Stream.of(invokedOn.getClass().getDeclaredFields())
				      .filter(f -> f.isAnnotationPresent(annotationClass) && returnType.isAssignableFrom(f.getType()) && f.trySetAccessible())
				      .map(f -> {
					      try {
						      return (T) f.get(invokedOn);
					      } catch (IllegalAccessException e) {
						      throw new RuntimeException(e);
					      }
				      }),
				Stream.of(invokedOn.getClass().getDeclaredMethods())
				      .filter(m -> m.isAnnotationPresent(annotationClass) && returnType.isAssignableFrom(m.getReturnType()) && m.trySetAccessible())
				      .map(m -> {
					      try {
						      return (T) m.invoke(invokedOn);
					      } catch (IllegalAccessException | InvocationTargetException e) {
						      throw new RuntimeException(e);
					      }
				      })
		).toList();
	}
	
	public <T, A extends Annotation> T getFirstAnnotadedValue(Class<A> annotationClass, Class<T> returnType, Object invokedOn) {
		try {
			return getAnnotadedValues(annotationClass, returnType, invokedOn).getFirst();
		} catch (NoSuchElementException e) {
			return null;
		}
	}
	
}