package krystal;

import com.google.common.io.Resources;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Log4j2
@UtilityClass
public class Tools {
	
	public <T> String concat(CharSequence delimeter, Stream<T> stream) {
		return stream.filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(delimeter));
	}
	
	public Set<Integer> expandGroupsOfRangesToInts(String groupsDelimeter, String rangesDelimeter, String ranges) {
		if (ranges == null) return Set.of();
		return Stream.of(ranges.split(groupsDelimeter)).flatMap(r -> {
			String[] range = r.trim().split(rangesDelimeter);
			return IntStream.rangeClosed(Integer.parseInt(range[0].trim()), Integer.parseInt(range[range.length - 1].trim())).boxed();
		}).collect(Collectors.toSet());
	}
	
	public String intsAsGroupsOfRanges(String groupsDelimeter, String rangesDelimeter, Integer... ints) {
		return groupSequentialIntegers(ints).stream()
		                                    .map(l -> {
			                                    if (l.size() > 1)
				                                    return l.getFirst() + rangesDelimeter + l.getLast();
			                                    else return l.getFirst().toString();
		                                    })
		                                    .collect(Collectors.joining(groupsDelimeter));
	}
	
	public Set<List<Integer>> groupSequentialIntegers(Integer... ints) {
		if (ints == null) return Set.of();
		
		val sequence = Arrays.stream(ints).sorted().toList();
		val result = new LinkedHashSet<List<Integer>>();
		val currentGroup = new LinkedList<Integer>();
		val prev = new AtomicInteger(sequence.getFirst());
		currentGroup.add(prev.get());
		
		sequence.forEach(i -> {
			if (i == prev.get())
				return;
			
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
		return Stream.of(str)
		             .flatMap(s -> Stream.of(s.split("\\|/")))
		             .collect(Collectors.joining("/"));
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
	public URL getResource(String... path) {
		return loadResource(concatAsURIPath(path));
	}
	
}