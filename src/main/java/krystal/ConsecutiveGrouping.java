package krystal;

import lombok.Getter;
import lombok.Setter;
import lombok.val;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Wrapper for grouping successive items by common property, with possible any-other repeats.
 */
@Getter
public class ConsecutiveGrouping<I> {
	
	private final List<I> items;
	private @Setter Object commonProperty;
	
	public ConsecutiveGrouping() {
		commonProperty = null;
		items = new LinkedList<>();
	}
	
	public ConsecutiveGrouping(final Object propertyValue, final List<I> items) {
		commonProperty = propertyValue;
		this.items = items;
	}
	
	public ConsecutiveGrouping<I> copy() {
		return new ConsecutiveGrouping<>(commonProperty, new LinkedList<>(items));
	}
	
	public void reset(final Object propertyValue) {
		commonProperty = propertyValue;
		items.clear();
	}
	
	/**
	 * Split items to successive groups based on common property value.
	 */
	public static <I> List<ConsecutiveGrouping<I>> getGroups(Stream<I> items, Function<I, Object> groupByValue) {
		
		final List<ConsecutiveGrouping<I>> consecutiveGroupings = new LinkedList<>();
		final ConsecutiveGrouping<I> currentConsecutiveGrouping = new ConsecutiveGrouping<>();
		items.forEach(o -> {
			val propertyValue = groupByValue.apply(o);
			// initialize scope once
			if (currentConsecutiveGrouping.getCommonProperty() == null) currentConsecutiveGrouping.reset(propertyValue);
			
			// assignments
			if (currentConsecutiveGrouping.getCommonProperty().equals(propertyValue))
				currentConsecutiveGrouping.getItems().add(o);
			else {
				consecutiveGroupings.add(currentConsecutiveGrouping.copy());
				currentConsecutiveGrouping.reset(propertyValue);
				currentConsecutiveGrouping.getItems().add(o);
			}
		});
		// add last processed scope
		consecutiveGroupings.add(currentConsecutiveGrouping);
		return consecutiveGroupings;
	}
	
}