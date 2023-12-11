package krystal;

import com.google.common.io.Resources;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
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
public class Tools {
	
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
	
	public String joinAsURIPath(String... str) {
		return Stream.of(str)
		             .flatMap(s -> Stream.of(s.split("\\|/")))
		             .collect(Collectors.joining("/"));
	}
	
	/**
	 * Attempts to find resource directly (absolute or relative) and then by {@link Resources#getResource(String)}.
	 */
	public URL loadResource(String path) {
		if (Files.exists(Path.of(path)))
			try {
				return URI.create(path).toURL();
			} catch (MalformedURLException ignored) {
			}
		return Resources.getResource(path);
	}
	
	/**
	 * Conveniently links paths to form URI string and then loads resource.
	 *
	 * @see #loadResource(String)
	 */
	public URL getResource(String... path) {
		return loadResource(joinAsURIPath(path));
	}
	
}