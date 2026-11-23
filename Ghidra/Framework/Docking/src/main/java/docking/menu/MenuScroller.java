/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package docking.menu;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;

import generic.theme.ThemeManager;

/**
 * Lets a popup menu that is taller than the screen scroll.
 * <p>
 * Swing moves a popup menu so that it stays on the screen, but it does not shrink or scroll a
 * menu that is taller than the screen, so the items at the end of such a menu cannot be reached.
 * When such a menu opens, this class shows only the items that fit on the screen, with an arrow
 * at the top and at the bottom.  The user scrolls the items by hovering over an arrow, with the
 * mouse wheel, or with the up and down keys at the first or last visible item.  When the menu
 * closes, all items are put back.
 * <p>
 * Menus that fit on the screen are not changed.  The FlatLaf look and feels scroll long menus
 * themselves, so this class does nothing for them.
 */
public class MenuScroller {

	private static final String CLIENT_PROPERTY_KEY = MenuScroller.class.getName();
	private static final int HOVER_SCROLL_DELAY_MS = 60;
	private static final int ARROW_HEIGHT = 14;
	private static final int ARROW_SIZE = 4;

	private final JPopupMenu popup;
	private final ScrollArrow upArrow = new ScrollArrow(-1);
	private final ScrollArrow downArrow = new ScrollArrow(1);
	private final Box.Filler filler =
		new Box.Filler(new Dimension(), new Dimension(), new Dimension());
	private final Timer hoverTimer;
	private final MouseWheelListener wheelListener = this::mouseWheelMoved;
	private final MenuKeyListener keyListener = new ItemKeyListener();

	/** All items of the menu while it is shown scrolled; null otherwise */
	private Component[] items;
	private int[] itemHeights;
	private int contentWidth;
	private int contentHeight;

	/** The items shown are those from first (inclusive) to end (exclusive) */
	private int first;
	private int end;

	private int hoverDirection;
	private double wheelRemainder;

	/**
	 * Lets the given popup menu scroll when it is taller than the screen. Installing it more than
	 * once has no further effect.
	 * @param popup the popup menu
	 */
	public static void install(JPopupMenu popup) {
		if (popup.getClientProperty(CLIENT_PROPERTY_KEY) != null) {
			return;
		}
		MenuScroller scroller = new MenuScroller(popup);
		popup.putClientProperty(CLIENT_PROPERTY_KEY, scroller);
		popup.addPopupMenuListener(scroller.new ScrollerPopupListener());
	}

	private MenuScroller(JPopupMenu popup) {
		this.popup = popup;
		hoverTimer = new Timer(HOVER_SCROLL_DELAY_MS, e -> scrollBy(hoverDirection));
		hoverTimer.setInitialDelay(0);
	}

	private void menuWillOpen() {
		restoreItems();
		if (!isScrollingSupported()) {
			return;
		}

		Rectangle screen = getUsableScreenBounds();
		Dimension menuSize = popup.getPreferredSize();
		if (menuSize.height <= screen.height) {
			return;
		}

		Component[] components = popup.getComponents();
		Insets insets = popup.getInsets();
		int arrowsHeight =
			upArrow.getPreferredSize().height + downArrow.getPreferredSize().height;
		int available = screen.height - insets.top - insets.bottom - arrowsHeight;
		if (components.length == 0 || available <= 0) {
			return;
		}

		// the item sizes must be taken while the items are in the menu, as the Docking menu
		// item UI uses the menu to align the item text
		itemHeights = new int[components.length];
		for (int i = 0; i < components.length; i++) {
			itemHeights[i] = components[i].getPreferredSize().height;
		}
		items = components;
		contentWidth = menuSize.width - insets.left - insets.right;
		contentHeight = available;
		first = 0;
		wheelRemainder = 0;

		for (Component item : items) {
			if (item instanceof JMenuItem menuItem) {
				menuItem.addMenuKeyListener(keyListener);
			}
		}
		popup.addMouseWheelListener(wheelListener);
		showItemsFrom(0);
	}

	private boolean isScrollingSupported() {
		if (GraphicsEnvironment.isHeadless()) {
			return false;
		}

		// the native macOS menu bar shows its menus itself
		if (Boolean.getBoolean("apple.laf.useScreenMenuBar")) {
			return false;
		}

		ThemeManager themeManager = ThemeManager.getInstance();
		return themeManager == null || !themeManager.isUsingFlatUI();
	}

	private Rectangle getUsableScreenBounds() {
		Component invoker = popup.getInvoker();
		GraphicsConfiguration gc = invoker == null ? null : invoker.getGraphicsConfiguration();
		if (gc == null) {
			gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
					.getDefaultScreenDevice()
					.getDefaultConfiguration();
		}
		Rectangle bounds = gc.getBounds();
		Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		bounds.y += screenInsets.top;
		bounds.height -= screenInsets.top + screenInsets.bottom;
		return bounds;
	}

	private void restoreItems() {
		if (items == null) {
			return;
		}

		hoverTimer.stop();
		popup.removeMouseWheelListener(wheelListener);
		popup.removeAll();
		for (Component item : items) {
			popup.add(item);
			if (item instanceof JMenuItem menuItem) {
				menuItem.removeMenuKeyListener(keyListener);
			}
		}
		items = null;
		itemHeights = null;
		popup.revalidate();
	}

	/**
	 * Shows the items that fit, starting at the given index. The filler keeps the menu the same
	 * size for every start index, so the popup window does not have to be resized.
	 * @param start the index of the first item to show
	 */
	private void showItemsFrom(int start) {
		first = Math.max(0, Math.min(start, getLastStart()));
		popup.removeAll();
		popup.add(upArrow);

		int used = 0;
		int i = first;
		while (i < items.length && (i == first || used + itemHeights[i] <= contentHeight)) {
			popup.add(items[i]);
			used += itemHeights[i];
			i++;
		}
		end = i;

		Dimension fill = new Dimension(contentWidth, Math.max(0, contentHeight - used));
		filler.changeShape(fill, fill, fill);
		popup.add(filler);
		popup.add(downArrow);

		upArrow.setEnabled(first > 0);
		downArrow.setEnabled(end < items.length);
		popup.revalidate();
		popup.repaint();
	}

	/**
	 * {@return the start index that shows the given item as the last visible item}
	 * @param last the index of the item
	 */
	private int getStartEndingAt(int last) {
		int start = last;
		int used = itemHeights[last];
		while (start > 0 && used + itemHeights[start - 1] <= contentHeight) {
			start--;
			used += itemHeights[start];
		}
		return start;
	}

	private int getLastStart() {
		return getStartEndingAt(items.length - 1);
	}

	private void scrollBy(int count) {
		if (items == null || count == 0) {
			return;
		}
		int newFirst = Math.max(0, Math.min(first + count, getLastStart()));
		if (newFirst == first) {
			return;
		}
		closeSubMenus();
		showItemsFrom(newFirst);
	}

	/**
	 * Ends the selection path at this menu, so that no item that is about to be hidden keeps its
	 * sub menu open.
	 */
	private void closeSubMenus() {
		MenuSelectionManager manager = MenuSelectionManager.defaultManager();
		MenuElement[] path = manager.getSelectedPath();
		for (int i = 0; i < path.length - 1; i++) {
			if (path[i] == popup) {
				MenuElement[] newPath = new MenuElement[i + 1];
				System.arraycopy(path, 0, newPath, 0, i + 1);
				manager.setSelectedPath(newPath);
				return;
			}
		}
	}

	private void mouseWheelMoved(MouseWheelEvent e) {
		// touch pads send many small rotations; scroll one item for each whole unit
		wheelRemainder += e.getPreciseWheelRotation();
		int count = (int) wheelRemainder;
		wheelRemainder -= count;
		scrollBy(count);
		e.consume();
	}

	private void moveSelection(MenuKeyEvent e, int direction) {
		MenuElement[] path = e.getPath();
		MenuElement[] selectedPath = e.getMenuSelectionManager().getSelectedPath();
		int popupIndex = selectedPath.length - 2;
		if (popupIndex < 0 || selectedPath[popupIndex] != popup || path.length < 2 ||
			path[path.length - 2] != popup) {
			return;
		}

		Component selected = selectedPath[popupIndex + 1].getComponent();
		int index = indexOfItem(selected);
		if (index < 0) {
			return;
		}

		int next = findSelectableItem(index, direction);
		if (next < 0 || (next >= first && next < end)) {
			// Swing selects the next visible item itself
			return;
		}

		closeSubMenus();
		showItemsFrom(next < first ? next : getStartEndingAt(next));
		MenuElement[] newPath = new MenuElement[popupIndex + 2];
		System.arraycopy(selectedPath, 0, newPath, 0, popupIndex + 1);
		newPath[popupIndex + 1] = (MenuElement) items[next];
		e.getMenuSelectionManager().setSelectedPath(newPath);
		e.consume();
	}

	private int indexOfItem(Component c) {
		for (int i = 0; i < items.length; i++) {
			if (items[i] == c) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * {@return the index of the next item that can be selected in the given direction, wrapping
	 * at the ends of the menu as Swing does; -1 if there is none}
	 * @param from the index of the selected item
	 * @param direction 1 for down, -1 for up
	 */
	private int findSelectableItem(int from, int direction) {
		for (int n = 1; n < items.length; n++) {
			int i = Math.floorMod(from + n * direction, items.length);
			Component c = items[i];
			if (c instanceof MenuElement && c.isEnabled() && c.isVisible()) {
				return i;
			}
		}
		return -1;
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	private class ScrollerPopupListener implements PopupMenuListener {
		@Override
		public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
			menuWillOpen();
		}

		@Override
		public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			restoreItems();
		}

		@Override
		public void popupMenuCanceled(PopupMenuEvent e) {
			restoreItems();
		}
	}

	/**
	 * Scrolls past the first or last visible item when the user moves the selection with the
	 * keyboard. Swing sends the key to every item of the menu; the first call that scrolls
	 * consumes the key, the others change nothing.
	 */
	private class ItemKeyListener implements MenuKeyListener {
		@Override
		public void menuKeyPressed(MenuKeyEvent e) {
			if (items == null) {
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_DOWN) {
				moveSelection(e, 1);
			}
			else if (e.getKeyCode() == KeyEvent.VK_UP) {
				moveSelection(e, -1);
			}
		}

		@Override
		public void menuKeyReleased(MenuKeyEvent e) {
			// not used
		}

		@Override
		public void menuKeyTyped(MenuKeyEvent e) {
			// not used
		}
	}

	/**
	 * The arrow at the top or bottom of a scrolled menu. Hovering over it scrolls the menu.
	 */
	private class ScrollArrow extends JComponent {
		private final int direction;

		ScrollArrow(int direction) {
			this.direction = direction;
			setOpaque(false);
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseEntered(MouseEvent e) {
					hoverDirection = ScrollArrow.this.direction;
					hoverTimer.restart();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hoverTimer.stop();
				}
			});
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(0, ARROW_HEIGHT);
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Short.MAX_VALUE, ARROW_HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Color color = UIManager.getColor(
				isEnabled() ? "MenuItem.foreground" : "MenuItem.disabledForeground");
			if (color == null) {
				color = isEnabled() ? Color.BLACK : Color.GRAY;
			}

			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				int cx = getWidth() / 2;
				int cy = getHeight() / 2;
				int tip = cy + direction * ARROW_SIZE / 2;
				int base = cy - direction * ARROW_SIZE / 2;
				g2.fillPolygon(new int[] { cx - ARROW_SIZE, cx + ARROW_SIZE, cx },
					new int[] { base, base, tip }, 3);
			}
			finally {
				g2.dispose();
			}
		}
	}
}
