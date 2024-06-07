package krystal;

import com.google.common.base.Strings;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.val;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Methods to visualize objects into strings, like ASCII tables.
 */
@UtilityClass
public class StringRenderer {
	
	/**
	 * Renders provided rows and columns into appealing ASCII table.
	 */
	public String renderTable(List<String> columns, List<List<String>> rows) {
		
		val render = new StringBuilder();
		val offset = System.lineSeparator() + " ".repeat(4);
		val minWidths = calculateMinWidths(columns, rows);
		int totalWidth = minWidths.stream().mapToInt(Integer::intValue).sum() + 3 * (columns.size() - 1) + 6;
		val horizontalLine = "-".repeat(totalWidth);
		
		render.append(offset).append(horizontalLine);
		render.append(offset).append(renderRow(columns, minWidths));
		render.append(offset).append(horizontalLine);
		
		Stream.of(rows).flatMap(Collection::stream)
		      .forEach(r -> render.append(offset).append(renderRow(r, minWidths)));
		render.append(offset).append(horizontalLine).append(System.lineSeparator());
		
		return render.toString();
	}
	
	/**
	 * Renders a single row of values with minimum width applied to each.
	 */
	public String renderRow(List<String> row, List<Integer> minWidths) {
		val result = new StringBuilder();
		
		result.append("*| ");
		for (int i = 0; i < minWidths.size(); i++) {
			result.append(Strings.padEnd(Strings.nullToEmpty(row.get(i)), minWidths.get(i), ' '));
			if (i < minWidths.size() - 1)
				result.append(" | ");
		}
		result.append(" |*");
		
		return result.toString();
	}
	
	/**
	 * Renders provided objects fields as ASCII table.
	 *
	 * @see SkipRender @SkipRender
	 * @see #renderTable(List, List)
	 */
	public String renderObjects(@NonNull List<?> objects) {
		if (objects.isEmpty()) return "StringRenderer: Provided list of objects is empty.";
		val clazz = objects.getFirst().getClass();
		val columns = Arrays.stream(clazz.getDeclaredFields()).filter(f -> !f.isAnnotationPresent(SkipRender.class)).map(Field::getName).toList();
		val rows = objects.stream()
		                  .map(o -> columns.stream()
		                                   .map(c -> {
			                                   try {
				                                   val f = clazz.getDeclaredField(c);
				                                   f.setAccessible(true);
				                                   return String.valueOf(f.get(o));
			                                   } catch (NoSuchFieldException | IllegalAccessException e) {
				                                   return "Exception!";
			                                   }
		                                   })
		                                   .toList())
		                  .toList();
		
		return renderTable(columns, rows);
	}
	
	/**
	 * Renders provided 2D {@link Map} {@code Map<?, Map<?, ?>>} as ASCII table.
	 * The number of columns is derived from number of distinct elements (keys) of second dimension Maps.
	 * Second dimension Maps do not have to hold equal numbers of elements.
	 * Uses {@link String#valueOf(Object)} to render values.
	 */
	public String render2DMap(@NonNull Map<?, Map<?, ?>> map) {
		if (map.isEmpty()) return "StringRenderer: Provided 2D map is empty.";
		
		val mapCastedToString = map.entrySet().stream().collect(Collectors.toMap(
				Entry::getKey,
				e -> e.getValue().entrySet().stream().collect(Collectors.toMap(
						e2 -> String.valueOf(e2.getKey()),
						e2 -> String.valueOf(e2.getValue()),
						(a, b) -> a,
						HashMap::new
				))
		));
		
		val columns = mapCastedToString.values().stream()
		                               .flatMap(m -> m.keySet().stream())
		                               .distinct()
		                               .collect(Collectors.toCollection(LinkedList::new));
		
		val firstColumn = map.keySet().stream().findAny().map(o -> o.getClass().getSimpleName()).orElse("Key object");
		columns.addFirst(firstColumn);
		
		val rows = mapCastedToString.entrySet()
		                            .stream()
		                            .map(e -> {
			                            val entries = e.getValue();
			                            entries.put(firstColumn, String.valueOf(e.getKey()));
			                            return columns.stream().map(entries::get).toList();
		                            })
		                            .toList();
		
		return renderTable(columns, rows);
	}
	
	/**
	 * Given the rows and columns, calculates the minimum widths for each column based on maximum lengths of values in each row.
	 */
	public List<Integer> calculateMinWidths(List<String> columns, List<List<String>> rows) {
		val c = columns.size();
		val minWidths = new HashMap<Integer, Integer>(c);
		
		for (int i = 0; i < c; i++) minWidths.put(i, Integer.MIN_VALUE);
		
		Stream.of(List.of(columns), rows)
		      .flatMap(Collection::stream)
		      .map(l -> l.stream().map(s -> s == null ? 0 : s.length()).toList())
		      .forEach(s -> {
			      for (int i = 0; i < c; i++) {
				      val width = s.get(i);
				      if (width > minWidths.get(i))
					      minWidths.put(i, width);
			      }
		      });
		
		return minWidths.values().stream().toList();
	}
	
	/**
	 * Mark fields which will be filtered-out for {@link #renderObjects(List)};
	 */
	@Target(ElementType.FIELD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface SkipRender {
	
	}
	
}