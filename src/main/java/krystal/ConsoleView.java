package krystal;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Log4j2
public class ConsoleView {
	
	private final JFrame frame;
	private final JEditorPane output;
	private final @Getter Document contentDocument;
	private final JScrollPane scroll;
	private final JCheckBox optionAutoScroll;
	private final JTextField commandPrompt;
	private final List<String> commandStack = new ArrayList<>(List.of(""));
	private int commandSelected;
	
	/**
	 * Creates a simple Swing window to output log events.
	 */
	public ConsoleView(@Nullable String windowTitle, Logger logger, PatternLayout loggingPattern, Consumer<String> commandParser) {
		/*
		 * Render Swing Console
		 */
		val defaultFont = new Font("monospaced", Font.PLAIN, 14);
		
		val outerColor = Color.getHSBColor(0.5278f, 0.15f, 0.20f);
		val innerColor = Color.getHSBColor(0.5278f, 0.10f, 0.16f);
		val middleColor = Color.getHSBColor(0.5278f, 0.15f, 0.25f);
		
		output = new JEditorPane();
		output.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 5));
		output.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		output.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		output.setAlignmentY(JComponent.TOP_ALIGNMENT);
		output.setBackground(innerColor);
		output.setEditorKit(new HTMLEditorKit());
		output.setEditable(false);
		output.addFocusListener(new FocusListener() {
			@Override
			public void focusGained(FocusEvent e) {
				output.getCaret().setVisible(true);
			}
			
			@Override
			public void focusLost(FocusEvent e) {
				output.getCaret().setVisible(false);
			}
		});
		output.setCaretColor(new Color(255, 153, 51));
		((DefaultCaret) output.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE); // no auto-scrolling
		
		output.setContentType("text/html; charset=utf-8");
		contentDocument = createBaseDocument();
		setStyle(getDefaultStyle());
		
		output.setText(contentDocument.outerHtml());
		
		commandPrompt = new JTextField();
		commandPrompt.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		commandPrompt.setAlignmentY(JComponent.BOTTOM_ALIGNMENT);
		commandPrompt.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
		commandPrompt.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		commandPrompt.setFont(defaultFont);
		commandPrompt.setBackground(middleColor);
		commandPrompt.setForeground(Color.lightGray);
		commandPrompt.setCaretColor(Color.white);
		commandPrompt.addActionListener(_ -> {
			val command = commandPrompt.getText();
			if (Strings.isNullOrEmpty(command))
				return;
			commandStack.set(0, command);
			commandParser.accept(command);
			commandStack.addFirst("");
			commandPrompt.setText(null);
			commandPrompt.requestFocus();
		});
		commandPrompt.addKeyListener(new KeyListener() {
			
			@Override
			public void keyTyped(KeyEvent e) {
				commandSelected = 0;
				if (!Character.isISOControl(e.getKeyChar()))
					commandStack.set(0, commandPrompt.getText() + e.getKeyChar());
			}
			
			@Override
			public void keyPressed(KeyEvent e) {
				val key = e.getKeyCode();
				val historyMax = commandStack.size() - 1;
				
				if (key == KeyEvent.VK_UP) {
					commandPrompt.setText(commandStack.get(commandSelected == historyMax ? historyMax : ++commandSelected));
				}
				
				if (key == KeyEvent.VK_DOWN) {
					commandPrompt.setText(commandStack.get(commandSelected > 0 ? --commandSelected : 0));
				}
			}
			
			@Override
			public void keyReleased(KeyEvent e) {
			
			}
		});
		
		scroll = new JScrollPane(
				output,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, //
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED //
		);
		scroll.setBorder(BorderFactory.createLineBorder(outerColor, 8));
		scroll.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		scroll.setAlignmentY(JComponent.CENTER_ALIGNMENT);
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		scroll.setBackground(innerColor);
		
		Supplier<BasicScrollBarUI> scrollBar = () -> new BasicScrollBarUI() {
			@Override
			protected JButton createDecreaseButton(int orientation) {
				return new BasicArrowButton(orientation, middleColor, middleColor, innerColor.darker(), middleColor);
			}
			
			@Override
			protected JButton createIncreaseButton(int orientation) {
				return new BasicArrowButton(orientation, middleColor, middleColor, innerColor.darker(), middleColor);
			}
			
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = middleColor;
				this.trackColor = innerColor;
				this.trackHighlightColor = innerColor;
			}
		};
		
		scroll.getVerticalScrollBar().setUI(scrollBar.get());
		scroll.getHorizontalScrollBar().setUI(scrollBar.get());
		
		val options = new JPanel();
		options.setLayout(new BoxLayout(options, BoxLayout.X_AXIS));
		options.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		options.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		options.setAlignmentY(JComponent.TOP_ALIGNMENT);
		options.setBackground(outerColor);
		options.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		
		optionAutoScroll = new JCheckBox("Autoscroll to bottom");
		optionAutoScroll.setSelected(true);
		optionAutoScroll.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		optionAutoScroll.setForeground(Color.white);
		optionAutoScroll.setOpaque(false);
		optionAutoScroll.addActionListener(_ -> commandPrompt.requestFocus());
		options.add(optionAutoScroll);
		
		options.add(Box.createHorizontalGlue());
		
		val btnScroll = new JButton("Bottom");
		btnScroll.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
		btnScroll.addActionListener(_ -> scrollToBottom());
		btnScroll.setMaximumSize(new Dimension(100, 20));
		options.add(btnScroll);
		
		frame = new JFrame();
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setSize(1350, 700);
		frame.setTitle(Optional.ofNullable(windowTitle).orElse("Krystal Frame: Console View"));
		frame.setLocationRelativeTo(null);
		frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.Y_AXIS));
		
		val canvas = new JPanel();
		canvas.setLayout(new BoxLayout(canvas, BoxLayout.Y_AXIS));
		canvas.setBorder(BorderFactory.createEmptyBorder(5, 5, 25, 5));
		canvas.setBackground(outerColor);
		canvas.add(options);
		canvas.add(scroll);
		canvas.add(commandPrompt);
		
		frame.add(canvas);
		
		frame.revalidate();
		frame.setVisible(true);
		commandPrompt.requestFocus();
		
		/*
		 * Set-up appender
		 */
		
		val consoleAppenderName = "ConsoleView";
		
		Optional.ofNullable(logger.getAppenders().get(consoleAppenderName)).ifPresent(a -> {
			a.stop();
			val context = logger.getContext();
			context.getConfiguration().getRootLogger().removeAppender(consoleAppenderName);
		});
		
		val appender = new AbstractAppender(consoleAppenderName, null, loggingPattern, true, null) {
			
			@Override
			public void append(LogEvent event) {
				EventQueue.invokeLater(() -> {
					new Element("div").appendTo(getContent())
					                  .addClass(event.getLevel().name().toLowerCase())
					                  .appendElement("pre")
					                  .append(loggingPattern.toSerializable(event));
					revalidate();
				});
			}
			
		};
		
		logger.addAppender(appender);
		appender.start();
		
		log.fatal("=== Custom Console Viewer created and logger wired.");
	}
	
	/**
	 * Scroll down to last message and focus on input field.
	 */
	public void scrollToBottom() {
		val bar = scroll.getVerticalScrollBar();
		bar.setValue(bar.getMaximum());
		commandPrompt.requestFocus();
	}
	
	/**
	 * Refresh the content and swing window and scroll to bottom if set.
	 */
	public void revalidate() {
		output.setText(contentDocument.outerHtml());
		frame.revalidate();
		if (optionAutoScroll.isSelected()) scrollToBottom();
	}
	
	/**
	 * Clear log messages from the view.
	 */
	public void clear() {
		getContent().children().remove();
		revalidate();
	}
	
	/**
	 * @see JFrame#dispose()
	 */
	public void dispose() {
		frame.dispose();
	}
	
	/*
	 * Styling
	 */
	
	/**
	 * Set {@code <style>} within the {@code <head>} tag.
	 */
	public void setStyle(String styles) {
		Optional.ofNullable(contentDocument.head().getElementsByTag("style").first())
		        .orElseGet(() -> new Element("style").attr("type", "text/css").appendTo(contentDocument.head()))
		        .text(styles);
	}
	
	/**
	 * Add style sheet file reference to the {@code <head>} tag of the view document.
	 */
	public void addStyleSheet(String styleSheetLink) {
		if (getStyleSheet(styleSheetLink).isEmpty()) {
			contentDocument.head()
			               .appendElement("link")
			               .attr("rel", "stylesheet")
			               .attr("type", "text/css")
			               .attr("href", styleSheetLink)
			;
		}
	}
	
	public void removeStyleSheet(String styleSheetLink) {
		getStyleSheet(styleSheetLink).ifPresent(Node::remove);
	}
	
	private Optional<Element> getStyleSheet(String styleSheetLink) {
		return Optional.ofNullable(
				contentDocument.head()
				               .getElementsByTag("link")
				               .select("[rel=\"stylesheet\"]")
				               .select("[href=\"" + styleSheetLink + "\"]")
				               .first()
		);
	}
	
	private String getDefaultStyle() {
		return """
						                    body {
						                        color: rgb(245,245,245);
						                    }
						                    pre {
						                        margin: 0 px;
						                    }
					                        main {
				                                display: flex;
						                        flex-direction: column;
						                        font-size: 11 px;
						                        font-family: monospaced;
						                    }
						                    .info {color: rgb(0, 153, 255);}
						                    .fatal {color: rgb(255,51,0);}
						                    .test {color: rgb(255,204,0);}
						                    .console {color: rgb(51,204,51);}
						                    .trace {color: rgb(105,105,105);}
						                    .warn {color: rgb(255,153,51);}
						                    .error {color: rgb(255,80,80);}
				""";
	}
	
	public Element getContent() {
		return Optional.ofNullable(contentDocument.body().getElementsByTag("main").getFirst())
		               .orElseGet(() -> new Element("main").appendTo(contentDocument.body()));
	}
	
	private Document createBaseDocument() {
		return Document.createShell("")
		               .body()
		               .appendElement("main")
		               .ownerDocument();
	}
	
}