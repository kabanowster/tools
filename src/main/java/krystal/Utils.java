package krystal;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Utility methods
 */
@Log4j2
@UtilityClass
public class Utils {
	
	public <T> String concat(CharSequence delimeter, Stream<T> stream) {
		return stream.filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(delimeter));
	}
	
	public Set<Integer> splitRangesToInts(String ranges) {
		if (ranges == null) return Set.of();
		return Stream.of(ranges.split(";")).flatMap(r -> {
			// convert strings to Integers, including ranges
			String[] range = r.split("-");
			return IntStream.rangeClosed(Integer.parseInt(range[0]), Integer.parseInt(range[range.length - 1])).boxed();
		}).collect(Collectors.toSet());
	}
	
}
