package krystal;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import lombok.Getter;

import java.util.stream.Stream;

/**
 * <p>
 * Stores contraints, to use with JavaFX GridPane, similarly to GridBagConstraints in Swing. Allows settings with convenient, functional manner. Use built-in <b>add()</b> method to add nodes to GridPane or
 * <b>setConstraints()</b> method to just pass settings on them. Use relative indexes settings (<b>relCol()</b> / <b>relRow()</b>) for automated indexes increament.
 * </p>
 * <pre>
 * <b>Defaults:</b>
 * columnIndex & rowIndex = 0;
 * columnspan & rowspan = 1;
 * alignments = CENTER;
 * grows = NEVER;
 * margin = 0;
 * </pre>
 *
 * @author Wiktor Kabanow
 */
@Getter
public class GridPaneContstraintsWrapper<T extends GridPane> {
	
	T grid;
	private int columnIndex, rowIndex, columnSpan, rowSpan;
	private boolean relCol, relRow;
	private HPos hAlignment;
	private VPos vAlignment;
	private Priority hGrow, vGrow;
	private Insets margin;
	
	public GridPaneContstraintsWrapper(T grid) {
		
		grid(grid).index(0).span(1).center().grow(Priority.NEVER).margin(Insets.EMPTY);
	}
	
	/**
	 * Index setting wrapper for both ColumnsMap and Row.
	 */
	public GridPaneContstraintsWrapper<T> index(int xy) {
		return index(xy, xy);
	}
	
	/**
	 * Index setting wrapper for ColumnsMap and Row.
	 */
	public GridPaneContstraintsWrapper<T> index(int x, int y) {
		return coli(x).rowi(y);
	}
	
	/**
	 * 0-based index for current ColumnsMap in the Grid.
	 */
	public GridPaneContstraintsWrapper<T> coli(int x) {
		columnIndex = Math.abs(x);
		relCol = false;
		
		return this;
	}
	
	/**
	 * 0-based index for current Row in the Grid.
	 */
	public GridPaneContstraintsWrapper<T> rowi(int y) {
		rowIndex = Math.abs(y);
		relRow = false;
		
		return this;
	}
	
	/**
	 * Span setting wrapper for both ColumnsMap and Row.
	 */
	public GridPaneContstraintsWrapper<T> span(int xy) {
		columnSpan = xy;
		rowSpan = xy;
		return this;
	}
	
	/**
	 * Alignment setting wrapper for CENTER.
	 */
	public GridPaneContstraintsWrapper<T> center() {
		vAlignment = VPos.CENTER;
		hAlignment = HPos.CENTER;
		return this;
	}
	
	/**
	 * Grow setting wrapper for both H and V. Please read the description for Horizontal priority.
	 *
	 * @see GridPaneContstraintsWrapper#hGrow(Priority)
	 */
	public GridPaneContstraintsWrapper<T> grow(Priority p) {
		vGrow = p;
		hGrow = p;
		return this;
	}
	
	/*
	 * Margin setting with specified Insets.
	 */
	public GridPaneContstraintsWrapper<T> margin(Insets ins) {
		margin = ins;
		return this;
	}
	
	/**
	 * Set the GridPane (or extends) to which the nodes will be added with add() method.
	 */
	public GridPaneContstraintsWrapper<T> grid(T grid) {
		this.grid = grid;
		return this;
	}
	
	/**
	 * Start a relative column index from given position (inclisively).
	 *
	 * @see GridPaneContstraintsWrapper#relCol()
	 */
	public GridPaneContstraintsWrapper<T> relCol(int x) {
		return coli(x).relCol();
	}
	
	/**
	 * Increases index relatively to the last used value. I.e.
	 * <b>coli(1).relCol()</b> will start with ColumnsMap 1 (inclusively) and increase the
	 * value with the next <b>setConstraints()</b> calls.
	 *
	 * @see GridPaneContstraintsWrapper#relCol(int)
	 */
	public GridPaneContstraintsWrapper<T> relCol() {
		if (!relCol)
			columnIndex--;
		relCol = true;
		return this;
	}
	
	/**
	 * Start a relative row index from given position (inclisively).
	 *
	 * @see GridPaneContstraintsWrapper#relRow()
	 */
	public GridPaneContstraintsWrapper<T> relRow(int y) {
		return rowi(y).relRow();
	}
	
	/**
	 * Increases index relatively to the last used value. I.e.
	 * <b>rowi(1).relRow()</b> will start with Row 1 (inclusively) and increase the
	 * value with the next <b>setConstraints()</b> calls.
	 *
	 * @see GridPaneContstraintsWrapper#relRow(int)
	 */
	public GridPaneContstraintsWrapper<T> relRow() {
		if (!relRow)
			rowIndex--;
		relRow = true;
		
		return this;
	}
	
	/**
	 * Targets next column relatively to the index. Can be used to finish relative index.
	 *
	 * @see GridPaneContstraintsWrapper#relCol()
	 */
	public GridPaneContstraintsWrapper<T> nextCol() {
		columnIndex++;
		relCol = false;
		
		return this;
	}
	
	/**
	 * Targets next row relatively to the index. Can be used to finish relative index.
	 *
	 * @see GridPaneContstraintsWrapper#relRow()
	 */
	public GridPaneContstraintsWrapper<T> nextRow() {
		rowIndex++;
		relRow = false;
		
		return this;
	}
	
	/**
	 * Column span setting.
	 */
	public GridPaneContstraintsWrapper<T> colSpan(int s) {
		columnSpan = s;
		return this;
	}
	
	/**
	 * Row span setting.
	 */
	public GridPaneContstraintsWrapper<T> rowSpan(int s) {
		rowSpan = s;
		return this;
	}
	
	/**
	 * Span setting wrapper for ColumnsMap and Row.
	 */
	public GridPaneContstraintsWrapper<T> span(int x, int y) {
		columnSpan = x;
		rowSpan = y;
		return this;
	}
	
	/**
	 * Horizontal alignment setting.
	 */
	public GridPaneContstraintsWrapper<T> hPos(HPos pos) {
		hAlignment = pos;
		return this;
	}
	
	/**
	 * Vertical alignment setting.
	 */
	public GridPaneContstraintsWrapper<T> vPos(VPos pos) {
		vAlignment = pos;
		return this;
	}
	
	/**
	 * Horizontal priority grow setting. This is taken into calculated in case two or more nodes sizes would overlap caused either by painting area being too small or resized bigger. It doesn't make nodes resize automatically to fill gaps. To make nodes
	 * fill the empty space automatically, set their MAX width/height to large values (i.e. Double.MAX_VALUE). Then this setting will determine which one will take more.
	 */
	public GridPaneContstraintsWrapper<T> hGrow(Priority p) {
		hGrow = p;
		return this;
	}
	
	/**
	 * Vertical grow setting. Please read the description for Horizontal priority.
	 *
	 * @see GridPaneContstraintsWrapper#hGrow(Priority)
	 */
	public GridPaneContstraintsWrapper<T> vGrow(Priority p) {
		vGrow = p;
		return this;
	}
	
	/**
	 * Margin setting with specified doubles (passed to Insets constructor).
	 */
	public GridPaneContstraintsWrapper<T> margin(double top, double right, double bottom, double left) {
		margin = new Insets(top, right, bottom, left);
		return this;
	}
	
	/**
	 * Margin setting with specified doubles (passed to Insets constructor).
	 */
	public GridPaneContstraintsWrapper<T> margin(double topbottom, double leftright) {
		margin = new Insets(topbottom, leftright, topbottom, leftright);
		return this;
	}
	
	/**
	 * Margin setting with specified doubles (passed to Insets constructor).
	 */
	public GridPaneContstraintsWrapper<T> margin(double ins) {
		margin = new Insets(ins);
		return this;
	}
	
	/**
	 * Add the nodes to the stored GridPane and apply setConstraints() method to each.
	 */
	public void add(Node... nodes) {
		Stream.of(nodes).forEach(node -> {
			grid.add(node, 0, 0); // can not parrarel because the GridPane cells arraylist is not synced...
		});
		
		setConstraints(nodes);
	}
	
	/**
	 * Apply current constraints to the nodes. Be aware if relative index (increases automatically)!
	 */
	public void setConstraints(Node... nodes) {
		Stream.of(nodes).forEach(
				node -> GridPane.setConstraints(
						node, columnIndex(), rowIndex(), columnSpan, rowSpan, hAlignment, vAlignment, hGrow, vGrow, margin
				));
	}
	
	private int columnIndex() {
		if (relCol)
			columnIndex++;
		return columnIndex;
		
		// return relCol == true ? columnIndex++ : columnIndex;
		// this produces error, seems like value is not getting added before return...
	}
	
	private int rowIndex() {
		if (relRow)
			rowIndex++;
		return rowIndex;
	}
	
}