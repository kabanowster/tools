package krystal;

import com.google.common.base.Strings;
import krystal.Skip.SkipTypes;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import java.lang.reflect.Field;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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
		val spacing = " ".repeat(4);
		val newLine = System.lineSeparator() + spacing;
		val minWidths = calculateMinWidths(columns, rows);
		int totalWidth = minWidths.stream().mapToInt(Integer::intValue).sum() + 3 * (columns.size() - 1) + 6;
		val horizontalLine = "-".repeat(totalWidth);
		
		// head
		render.append(newLine).append(horizontalLine).append(spacing);
		render.append(newLine).append(renderRow(columns, minWidths)).append(spacing);
		render.append(newLine).append(horizontalLine).append(spacing);
		
		// body
		Stream.of(rows).flatMap(Collection::stream)
		      .forEach(r -> render.append(newLine).append(renderRow(r, minWidths)).append(spacing));
		render.append(newLine).append(horizontalLine).append(spacing).append(System.lineSeparator());
		
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
	 * @see Skip
	 * @see #renderTable(List, List)
	 */
	public <T> String renderObjects(@NonNull List<T> objects) {
		if (objects.isEmpty()) return "StringRenderer: Provided list of objects is empty.";
		val clazz = objects.getFirst().getClass();
		val columns = Arrays.stream(clazz.getDeclaredFields()).filter(f -> !Tools.isSkipped(f, SkipTypes.render)).map(Field::getName).toList();
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
	public <A, B, C> String render2DMap(@NonNull Map<A, Map<B, C>> map) {
		if (map.isEmpty()) return "StringRenderer: Provided 2D map is empty.";
		
		val mapCastedToString = map.entrySet().stream().collect(Collectors.toMap(
				Entry::getKey,
				e -> e.getValue().entrySet().stream().collect(Collectors.toMap(
						e2 -> String.valueOf(e2.getKey()),
						e2 -> String.valueOf(e2.getValue()),
						(a, _) -> a,
						LinkedHashMap::new
				)),
				(a, _) -> a,
				LinkedHashMap::new
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
	 * Renders provided {@link List} of {@link Map Maps} as ASCII table.
	 * The number of columns is derived from number of distinct elements (keys) of Maps.
	 * Maps do not have to hold equal numbers of elements.
	 * Uses {@link String#valueOf(Object)} to render values.
	 */
	public <A, B> String renderMaps(@NonNull List<Map<A, B>> list) {
		if (list.isEmpty()) return "StringRenderer: Provided list of maps is empty.";
		
		val listCastedToString = list.stream()
		                             .map(map -> map.entrySet().stream()
		                                            .collect(Collectors.toMap(
				                                            e -> String.valueOf(e.getKey()),
				                                            e -> String.valueOf(e.getValue()),
				                                            (a, _) -> a,
				                                            LinkedHashMap::new
		                                            ))
		                             )
		                             .toList();
		
		val columns = listCastedToString.stream()
		                                .flatMap(m -> m.keySet().stream())
		                                .distinct()
		                                .collect(Collectors.toCollection(LinkedList::new));
		
		val rows = listCastedToString.stream()
		                             .map(m -> columns.stream().map(m::get).toList())
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
	 * Class to create string progress bars.
	 * {@link #render()} method aims to insert and update the created html element.
	 * {@link #dispose()} removes the element.
	 * You can get primitive renderer with {@link #simpleProgressBar()} and utilise {@link #toString(boolean) pure} values output.
	 */
	@Log4j2
	@Getter
	@Setter
	public abstract class ProgressRenderer {
		
		protected final String id;
		protected long target;
		protected long progress;
		protected String unit;
		protected List<Elements> elements;
		
		public ProgressRenderer() {
			this.id = UUID.randomUUID().toString();
			this.target = 100;
		}
		
		public abstract ProgressRenderer render();
		
		public abstract void dispose();
		
		public ProgressRenderer increment(long progressIncrement) {
			progress += progressIncrement;
			render();
			return this;
		}
		
		public ProgressRenderer update(long setProgress) {
			progress = setProgress;
			render();
			return this;
		}
		
		public void reset() {
			progress = 0;
		}
		
		public void reset(long newTarget) {
			reset();
			target = newTarget;
		}
		
		public static ProgressRenderer simpleProgressBar() {
			return new ProgressRenderer() {
				@Override
				public ProgressRenderer render() {
					return this;
				}
				
				@Override
				public void dispose() {
				}
			};
		}
		
		public static ProgressRenderer simpleProgressBar(UnaryOperator<ProgressRenderer> renderProgress, Consumer<ProgressRenderer> disposeProgress) {
			return new ProgressRenderer() {
				@Override
				public ProgressRenderer render() {
					return renderProgress.apply(this);
				}
				
				@Override
				public void dispose() {
					disposeProgress.accept(this);
				}
			};
		}
		
		/*
		 * Misc
		 */
		
		public ProgressRenderer without(Elements... elements) {
			val list = Arrays.stream(elements).toList();
			this.elements = Arrays.stream(Elements.values()).filter(e -> !list.contains(e)).toList();
			return this;
		}
		
		/*
		 * Elements renderers
		 */
		
		@Override
		public String toString() {
			return toString(false);
		}
		
		/**
		 * @param pure
		 * 		If {@code true}, body won't be enclosed in html tags.
		 */
		public String toString(boolean pure) {
			val pct = Math.round(((float) progress / target) * 100);
			val meatBall = getLeftIndentElement() + getPercentageElement(pct) + getProgressBarElement(pct) + getCurrentValueElement() + getTargetValueElement() + getUnitElement();
			if (pure) return meatBall;
			return "<div id=\"%s\" class=\"progress\"><pre>%s</pre></div>".formatted(getId(), meatBall);
		}
		
		public String getId() {
			return "progressbar_" + id;
		}
		
		private String getLeftIndentElement() {
			if (elements != null && !elements.contains(Elements.leftIndent)) return "";
			return "&#9;";
		}
		
		private String getPercentageElement(long pctValue) {
			if (elements != null && !elements.contains(Elements.percentage)) return "";
			return Strings.padStart(String.valueOf(pctValue), 3, ' ') + "%";
		}
		
		private String getProgressBarElement(long pctValue) {
			if (elements != null && !elements.contains(Elements.progressBar)) return "";
			
			val bar = new StringBuilder();
			for (int i = 1; i <= 100; i++) {
				if (i < pctValue) {
					bar.append("=");
				} else if (i == pctValue) {
					bar.append(">");
				} else {
					bar.append("-");
				}
			}
			
			return "[%s]".formatted(bar.toString());
		}
		
		private String getCurrentValueElement() {
			if (elements != null && !elements.contains(Elements.current)) return "";
			return " " + progress;
		}
		
		private String getTargetValueElement() {
			if (elements != null && !elements.contains(Elements.target)) return "";
			return " / " + target;
		}
		
		private String getUnitElement() {
			if (elements != null && !elements.contains(Elements.unit)) return "";
			return " " + unit;
		}
		
		public enum Elements {
			leftIndent, percentage, progressBar, current, target, unit
		}
		
	}
	
}