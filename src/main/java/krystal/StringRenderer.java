package krystal;

import com.google.common.base.Strings;
import lombok.experimental.UtilityClass;
import lombok.val;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

@UtilityClass
public class StringRenderer {
	
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
	
	public String renderRow(List<String> row, List<Integer> minWidths) {
		val result = new StringBuilder();
		
		result.append("*| ");
		for (int i = 0; i < minWidths.size(); i++) {
			result.append(Strings.padEnd(row.get(i), minWidths.get(i), ' '));
			if (i < minWidths.size() - 1)
				result.append(" | ");
		}
		result.append(" |*");
		
		return result.toString();
	}
	
	public List<Integer> calculateMinWidths(List<String> columns, List<List<String>> rows) {
		val c = columns.size();
		val minWidths = new HashMap<Integer, Integer>(c);
		
		for (int i = 0; i < c; i++) minWidths.put(i, Integer.MIN_VALUE);
		
		Stream.of(List.of(columns), rows).flatMap(Collection::stream)
		      .map(l -> l.stream().map(String::length).toList())
		      .forEach(s -> {
			      for (int i = 0; i < c; i++) {
				      val width = s.get(i);
				      if (width > minWidths.get(i))
					      minWidths.put(i, width);
			      }
		      });
		
		return minWidths.values().stream().toList();
	}
	
}