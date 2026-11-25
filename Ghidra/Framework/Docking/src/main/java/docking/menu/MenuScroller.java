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
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;

import generic.theme.GThemeDefaults.Colors;
import generic.theme.ThemeManager;

/**
 * Lets a popup menu that is taller than the screen scroll.
 * <p>
 * Swing moves a popup menu so that it stays on the screen, but it does not shrink or scroll a
 * menu that is taller than the screen, so the items at the end of such a menu cannot be reached.
 * This class makes such a menu behave as on macOS.  The menu opens next to its menu header, its
 * item or the requested location, and takes the space on that side only, from its first item on.
 * An arrow is shown at each end of the menu where items are out of view.  The user scrolls the
 * items by hovering over an arrow or with the mouse wheel.  Scrolling down first makes the menu
 * window grow into the rest of the screen height; only a menu as tall as the screen moves its
 * items.  The keys that select an item scroll it into view: up and down at the first or last
 * visible item, home and end, and the mnemonic keys of the items that are scrolled out of view.
 * When the menu closes, all items are put back.
 * <p>
 * Resizing a visible popup menu with the {@link JPopupMenu} methods replaces its window, so a
 * scrolled menu is shown in a window of its own (a heavy weight popup) and this class sets that
 * window's bounds itself.
 * <p>
 * Menus that fit on the screen are not changed.  The FlatLaf look and feels scroll long menus
 * themselves, so this class does nothing for them.
 * <p>
 * Use {@link GMenu} and {@link GPopupMenu}, which install it.
 */
class MenuScroller {

	private static final String CLIENT_PROPERTY_KEY = MenuScroller.class.getName();
	private static final int HOVER_SCROLL_DELAY_MS = 60;
	private static final int MIN_ARROW_ROW_HEIGHT = 8;

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
	private int arrowHeight;
	private boolean wasLightWeightPopupEnabled;

	/** The top and bottom of the screen area a menu may use, in screen coordinates */
	private int screenTop;
	private int screenBottom;

	/** The height inside the menu's insets for the arrows and items, now and at most */
	private int viewHeight;
	private int maxViewHeight;

	/**
	 * The bounds this class last gave the menu window; null until the menu grows.  On macOS a
	 * window reports a new frame later, so {@link Window#getBounds()} can return an earlier frame
	 * right after {@link Window#setBounds(Rectangle)}, and growing from it would lose a step.
	 */
	private Rectangle windowBounds;

	/** True if the menu opens above its requested location, so that it starts at the top */
	private boolean moveToScreenTop;

	/** The items shown are those from first (inclusive) to end (exclusive) */
	private int first;
	private int end;

	private double wheelRemainder;

	/** The item whose mnemonic key was pressed, to be chosen when the key is typed; -1 if none */
	private int pendingMnemonicItem = -1;

	/**
	 * Lets the given popup menu scroll when it is taller than the screen. Installing it more than
	 * once has no further effect.
	 * @param popup the popup menu
	 */
	static void install(JPopupMenu popup) {
		if (popup.getClientProperty(CLIENT_PROPERTY_KEY) != null) {
			return;
		}
		MenuScroller scroller = new MenuScroller(popup);
		popup.putClientProperty(CLIENT_PROPERTY_KEY, scroller);
		popup.addPopupMenuListener(scroller.new ScrollerPopupListener());
	}

	private MenuScroller(JPopupMenu popup) {
		this.popup = popup;
		hoverTimer = new Timer(HOVER_SCROLL_DELAY_MS, this::hoverTick);
		hoverTimer.setInitialDelay(0);
	}

	private void menuWillOpen() {
		restoreItems();
		if (!isScrollingSupported()) {
			return;
		}

		GraphicsConfiguration gc = getGraphicsConfiguration();
		Rectangle screen = gc.getBounds();
		Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		screenTop = screen.y + screenInsets.top;
		screenBottom = screen.y + screen.height - screenInsets.bottom;

		Dimension menuSize = popup.getPreferredSize();
		int height = getStartHeight(menuSize.height, screen, screenInsets);
		if (menuSize.height <= height) {
			return;
		}

		Component[] components = popup.getComponents();
		Insets insets = popup.getInsets();
		arrowHeight = upArrow.getPreferredSize().height;
		int view = height - insets.top - insets.bottom;
		if (components.length == 0 || view <= 2 * arrowHeight) {
			return;
		}
		moveRequestedLocation();

		// the item sizes must be taken while the items are in the menu, as the Docking menu
		// item UI uses the menu to align the item text
		itemHeights = new int[components.length];
		for (int i = 0; i < components.length; i++) {
			itemHeights[i] = components[i].getPreferredSize().height;
		}
		items = components;
		contentWidth = menuSize.width - insets.left - insets.right;
		viewHeight = view;
		maxViewHeight = Math.max(view, screenBottom - screenTop - insets.top - insets.bottom);
		first = 0;
		wheelRemainder = 0;
		windowBounds = null;

		// the window of a light weight popup cannot grow past the window it is in
		wasLightWeightPopupEnabled = popup.isLightWeightPopupEnabled();
		popup.setLightWeightPopupEnabled(false);

		for (Component item : items) {
			if (item instanceof JMenuItem menuItem) {
				menuItem.addMenuKeyListener(keyListener);
			}
		}
		popup.addMouseWheelListener(wheelListener);
		showItemsFrom(0);
		hoverTimer.start();
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

	/**
	 * {@return the height the menu gets when it opens}  A menu of the menu bar takes the space
	 * below or above its header, on the side Swing chooses, so that Swing does not move it over
	 * the header, where the mouse pointer selects an item right away.  A {@link GPopupMenu} takes
	 * the space below or above its requested location, whichever is larger.  Sub menus and other
	 * popup menus get the height of the screen.
	 * @param menuHeight the height of the whole menu
	 * @param screen the bounds of the screen
	 * @param screenInsets the insets of the screen
	 */
	private int getStartHeight(int menuHeight, Rectangle screen, Insets screenInsets) {
		moveToScreenTop = false;
		Component invoker = popup.getInvoker();
		if (invoker instanceof JMenu menu) {
			if (!menu.isTopLevelMenu() || !menu.isShowing()) {
				// a sub menu opens beside its item, so it covers neither the item nor the pointer
				return screenBottom - screenTop;
			}
			Point position = menu.getLocationOnScreen();
			int yOffset = UIManager.getInt("Menu.menuPopupOffsetY");
			int below = screenBottom - (position.y + menu.getHeight() + yOffset);
			if (menuHeight < below) {
				return below;
			}

			// the test of JMenu.getPopupMenuOrigin for a menu that does not fit below its header
			int screenHeight = screen.height - Math.abs(screenInsets.top + screenInsets.bottom);
			int menuY = position.y - Math.abs(screenInsets.top);
			boolean above = screenHeight - menu.getHeight() < 2 * (menuY - screen.y);
			return above ? position.y - yOffset - screenTop : below;
		}

		Point requested =
			popup instanceof GPopupMenu gPopup ? gPopup.getRequestedLocation() : null;
		if (requested == null || menuHeight <= screenBottom - screenTop) {
			// Swing moves a menu that fits on the screen so that it is fully shown
			return screenBottom - screenTop;
		}
		int below = screenBottom - requested.y;
		int above = requested.y - screenTop;
		if (below >= above) {
			return below;
		}
		moveToScreenTop = true;
		return above;
	}

	/**
	 * Moves a menu that is to end at its requested location to the top of the screen, before
	 * Swing shows it.  Swing would move it down until it ends at the bottom of the screen.
	 */
	private void moveRequestedLocation() {
		if (moveToScreenTop && popup instanceof GPopupMenu gPopup) {
			Point requested = gPopup.getRequestedLocation();
			gPopup.setLocation(requested.x, screenTop);
		}
	}

	private GraphicsConfiguration getGraphicsConfiguration() {
		Component invoker = popup.getInvoker();
		GraphicsConfiguration gc = invoker == null ? null : invoker.getGraphicsConfiguration();
		if (gc == null) {
			gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
					.getDefaultScreenDevice()
					.getDefaultConfiguration();
		}
		return gc;
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
		pendingMnemonicItem = -1;
		popup.setLightWeightPopupEnabled(wasLightWeightPopupEnabled);
		// JMenu places its popup by the size it had when last shown, if it has one; it must
		// place it by the size of all items
		popup.setSize(0, 0);
		popup.revalidate();
	}

	/**
	 * Shows the items that fit, starting at the given index, with an arrow at each end where
	 * items are out of view.  The filler keeps the menu at the view height.
	 * @param start the index of the first item to show
	 */
	private void showItemsFrom(int start) {
		first = Math.max(0, Math.min(start, getLastStart()));
		boolean showUp = first > 0;
		int space = viewHeight - (showUp ? arrowHeight : 0);
		boolean showDown = sumOfHeights(first, items.length) > space;
		if (showDown) {
			space -= arrowHeight;
		}

		popup.removeAll();
		if (showUp) {
			popup.add(upArrow);
		}
		int used = 0;
		int i = first;
		while (i < items.length && (i == first || used + itemHeights[i] <= space)) {
			popup.add(items[i]);
			used += itemHeights[i];
			i++;
		}
		end = i;

		Dimension fill = new Dimension(contentWidth, Math.max(0, space - used));
		filler.changeShape(fill, fill, fill);
		popup.add(filler);
		if (showDown) {
			popup.add(downArrow);
		}

		popup.revalidate();
		// lay out the items now: a sub menu opened in the same event is placed next to its item
		popup.validate();
		popup.repaint();
	}

	/**
	 * {@return the total height of the given items}
	 * @param from the index of the first item (inclusive)
	 * @param to the index after the last item (exclusive)
	 */
	private int sumOfHeights(int from, int to) {
		int sum = 0;
		for (int i = from; i < to; i++) {
			sum += itemHeights[i];
		}
		return sum;
	}

	/**
	 * {@return the start index that shows the given item as the last visible item}
	 * @param last the index of the item
	 */
	private int getStartEndingAt(int last) {
		int space = viewHeight - (last < items.length - 1 ? arrowHeight : 0);
		if (sumOfHeights(0, last + 1) <= space) {
			return 0;
		}
		space -= arrowHeight;
		int start = last;
		int used = itemHeights[last];
		while (start > 0 && used + itemHeights[start - 1] <= space) {
			start--;
			used += itemHeights[start];
		}
		return start;
	}

	private int getLastStart() {
		return getStartEndingAt(items.length - 1);
	}

	/**
	 * Scrolls the menu by the given number of items.
	 * @param count the number of items; negative scrolls up
	 * @return true if the menu scrolled; false if it is at the end already
	 */
	private boolean scrollBy(int count) {
		if (items == null || count == 0) {
			return false;
		}
		if (count > 0 ? end >= items.length : first == 0) {
			return false;
		}
		closeSubMenu();
		if (count > 0) {
			scrollToItem(Math.min(items.length - 1, end - 1 + count));
		}
		else {
			showItemsFrom(first + count);
		}
		return true;
	}

	/**
	 * Scrolls the menu as little as needed to show the given item.  Before items below the view
	 * are scrolled into it, the menu window grows, as far as the screen allows.
	 * @param index the index of the item
	 */
	private void scrollToItem(int index) {
		if (index < first) {
			showItemsFrom(index);
		}
		else if (index >= end) {
			growToShow(index);
			showItemsFrom(getStartEndingAt(index));
		}
	}

	/**
	 * Makes the menu window taller, so that it shows the items from the first one to the given
	 * one, or as many of them as fit on the screen.  It grows downwards as far as the screen
	 * allows, then upwards.  A menu whose items have been scrolled does not grow: it is as tall
	 * as the screen allows already.
	 * @param index the index of the item
	 */
	private void growToShow(int index) {
		if (first > 0 || viewHeight >= maxViewHeight) {
			return;
		}
		int needed = sumOfHeights(0, index + 1) + (index < items.length - 1 ? arrowHeight : 0);
		int delta = Math.min(maxViewHeight, needed) - viewHeight;
		if (delta <= 0) {
			return;
		}

		Window window = getPopupWindow();
		if (window == null) {
			maxViewHeight = viewHeight;
			return;
		}
		Rectangle bounds = windowBounds == null ? window.getBounds() : new Rectangle(windowBounds);
		int down = Math.min(delta, Math.max(0, screenBottom - (bounds.y + bounds.height)));
		bounds.y -= delta - down;
		bounds.height += delta;
		viewHeight += delta;
		windowBounds = new Rectangle(bounds);
		window.setBounds(bounds);
		window.validate();
	}

	/**
	 * {@return the window of its own that the menu is shown in; null if there is none}  A light
	 * weight popup is shown in the window of its invoker.
	 */
	private Window getPopupWindow() {
		Window window = SwingUtilities.getWindowAncestor(popup);
		Component invoker = popup.getInvoker();
		Window invokerWindow = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
		if (window == null || window == invokerWindow || !window.isShowing()) {
			return null;
		}
		return window;
	}

	private boolean isShown(int index) {
		return index >= first && index < end;
	}

	/**
	 * Closes the sub menu of the selected item of this menu, so that no item that is about to be
	 * hidden keeps its sub menu open.  The item itself stays selected, also when it is scrolled
	 * out of view; the next key press scrolls it back.
	 */
	private void closeSubMenu() {
		MenuSelectionManager manager = MenuSelectionManager.defaultManager();
		MenuElement[] path = manager.getSelectedPath();
		for (int i = 0; i < path.length; i++) {
			if (path[i] == popup) {
				if (i + 2 < path.length) {
					manager.setSelectedPath(Arrays.copyOf(path, i + 2));
				}
				return;
			}
		}
	}

	/**
	 * Scrolls the menu by one item while the mouse pointer is over an arrow.  The timer runs while
	 * the menu is shown scrolled.  It checks the pointer position instead of waiting for mouse
	 * enter and exit events: when the menu window grows or its items change, those events do not
	 * come, or come although the pointer did not move.
	 */
	private void hoverTick(ActionEvent e) {
		if (isPointerOver(downArrow)) {
			scrollBy(1);
		}
		else if (isPointerOver(upArrow)) {
			scrollBy(-1);
		}
	}

	/**
	 * {@return true if the mouse pointer is within the bounds of the given component on the
	 * screen}  {@link Component#getMousePosition()} is not used: it also returns null when it
	 * finds another window under the pointer, which it often does for a popup menu window.
	 * @param c the component
	 */
	private static boolean isPointerOver(Component c) {
		if (!c.isShowing()) {
			return false;
		}
		PointerInfo pointer = MouseInfo.getPointerInfo();
		if (pointer == null) {
			return false;
		}
		Rectangle bounds = new Rectangle(c.getLocationOnScreen(), c.getSize());
		return bounds.contains(pointer.getLocation());
	}

	private void mouseWheelMoved(MouseWheelEvent e) {
		// touch pads send many small rotations; scroll one item for each whole unit
		wheelRemainder += e.getPreciseWheelRotation();
		int count = (int) wheelRemainder;
		wheelRemainder -= count;
		scrollBy(count);
		e.consume();
	}

	/**
	 * {@return the key with the state of the menu it acts on; null if the key is not meant for
	 * the items of this menu}  It is meant for them when this menu is the deepest open menu.
	 * @param e the key event
	 */
	private MenuKey getMenuKey(MenuKeyEvent e) {
		MenuElement[] path = e.getMenuSelectionManager().getSelectedPath();
		int last = path.length - 1;
		int popupIndex;
		if (last >= 0 && path[last] == popup) {
			popupIndex = last;
		}
		else if (last >= 1 && path[last - 1] == popup) {
			popupIndex = last - 1;
		}
		else {
			return null;
		}
		int selected = popupIndex < last ? indexOfItem(path[last].getComponent()) : -1;
		return new MenuKey(e, path, popupIndex, selected);
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
	 * Scrolls the given item into view, selects it and consumes the key.
	 * @param key the key that selects the item
	 * @param index the index of the item
	 */
	private void selectItem(MenuKey key, int index) {
		scrollToItem(index);
		MenuElement[] newPath = Arrays.copyOf(key.selectedPath(), key.popupIndex() + 2);
		newPath[key.popupIndex() + 1] = (MenuElement) items[index];
		key.event().getMenuSelectionManager().setSelectedPath(newPath);
		key.event().consume();
	}

	private boolean isSelectable(Component c) {
		return c instanceof MenuElement && c.isVisible() &&
			(c.isEnabled() || UIManager.getBoolean("MenuItem.disabledAreNavigable"));
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
			if (isSelectable(items[i])) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * {@return the first item that can be selected, starting at the given item and going in the
	 * given direction without wrapping; -1 if there is none}
	 * @param from the index of the first item to check
	 * @param direction 1 for down, -1 for up
	 */
	private int findSelectableItemFrom(int from, int direction) {
		for (int i = from; i >= 0 && i < items.length; i += direction) {
			if (isSelectable(items[i])) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Moves the selection with the up or down key.  Swing moves it between the visible items
	 * itself; this method handles the moves to an item that is scrolled out of view.
	 */
	private void moveSelection(MenuKey key, int direction) {
		if (key.selected() < 0) {
			// Swing selects the first or last visible item
			return;
		}
		int next = findSelectableItem(key.selected(), direction);
		if (next >= 0 && !isShown(next)) {
			selectItem(key, next);
		}
	}

	/**
	 * Selects the first or last item that can be selected, for the home and end keys.
	 */
	private void selectEdgeItem(MenuKey key, int from, int direction) {
		int index = findSelectableItemFrom(from, direction);
		if (index >= 0) {
			selectItem(key, index);
		}
	}

	/**
	 * Handles the mnemonic keys as Swing does, but for all items, including those scrolled out of
	 * view: if one item matches, it is chosen; if several match, each press selects the next one.
	 * Swing only sees the visible items.
	 */
	private void selectByMnemonic(MenuKey key) {
		char keyChar = key.event().getKeyChar();
		if (!Character.isLetterOrDigit(keyChar)) {
			return;
		}
		char mnemonic = Character.toLowerCase(keyChar);

		List<Integer> matches = new ArrayList<>();
		int current = -1;
		for (int i = 0; i < items.length; i++) {
			if (!(items[i] instanceof JMenuItem item)) {
				continue;
			}
			if (item.isEnabled() && item.isVisible() &&
				Character.toLowerCase((char) item.getMnemonic()) == mnemonic) {
				matches.add(i);
			}
			if (item.isArmed() || item.isSelected()) {
				current = matches.size() - 1;
			}
		}

		if (matches.isEmpty()) {
			return;
		}
		if (matches.size() == 1) {
			int index = matches.get(0);
			if (isShown(index)) {
				// Swing chooses a visible item itself
				return;
			}
			// chosen when the key is typed, as Swing does, so that the typed key does not reach
			// the component that gets the focus back
			pendingMnemonicItem = index;
			key.event().consume();
			return;
		}
		selectItem(key, matches.get((current + 1) % matches.size()));
	}

	/**
	 * Chooses the item whose mnemonic key was pressed: opens its sub menu or clicks it.
	 */
	private void choosePendingMnemonicItem(MenuKeyEvent e) {
		int index = pendingMnemonicItem;
		pendingMnemonicItem = -1;
		MenuKey key = getMenuKey(e);
		if (key == null) {
			return;
		}
		MenuSelectionManager manager = e.getMenuSelectionManager();

		if (items[index] instanceof JMenu menu) {
			// open the sub menu next to its item, with its first item selected, as Swing does
			scrollToItem(index);
			JPopupMenu subPopup = menu.getPopupMenu();
			List<MenuElement> path =
				new ArrayList<>(Arrays.asList(key.selectedPath()).subList(0, key.popupIndex() + 1));
			path.add(menu);
			path.add(subPopup);
			for (MenuElement child : subPopup.getSubElements()) {
				if (isSelectable(child.getComponent())) {
					path.add(child);
					break;
				}
			}
			manager.setSelectedPath(path.toArray(MenuElement[]::new));
		}
		else if (items[index] instanceof JMenuItem item) {
			manager.clearSelectedPath();
			item.doClick();
		}
		e.consume();
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	/**
	 * A key pressed or typed while this menu is the deepest open menu, with the state of the menu
	 * when it arrived.
	 * @param event the key event
	 * @param selectedPath the selected path
	 * @param popupIndex the index of this menu in the path
	 * @param selected the index in {@link MenuScroller#items} of the selected item; -1 if none
	 */
	private record MenuKey(MenuKeyEvent event, MenuElement[] selectedPath, int popupIndex,
			int selected) {
		// data only
	}

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
	 * Handles the keys that select an item that is or may get scrolled out of view.  Swing sends
	 * a key to each visible item of the menu, before the menu's own key handling, until one
	 * consumes it.  The first call that acts consumes the key; the other calls change nothing.
	 */
	private class ItemKeyListener implements MenuKeyListener {
		@Override
		public void menuKeyPressed(MenuKeyEvent e) {
			pendingMnemonicItem = -1;
			if (items == null) {
				return;
			}
			MenuKey key = getMenuKey(e);
			if (key == null) {
				return;
			}

			// the selected item may have been scrolled out of view with the mouse
			if (key.selected() >= 0) {
				scrollToItem(key.selected());
			}

			switch (e.getKeyCode()) {
				case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> moveSelection(key, 1);
				case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> moveSelection(key, -1);
				case KeyEvent.VK_HOME -> selectEdgeItem(key, 0, 1);
				case KeyEvent.VK_END -> selectEdgeItem(key, items.length - 1, -1);
				default -> selectByMnemonic(key);
			}
		}

		@Override
		public void menuKeyReleased(MenuKeyEvent e) {
			// not used
		}

		@Override
		public void menuKeyTyped(MenuKeyEvent e) {
			if (items != null && pendingMnemonicItem >= 0) {
				choosePendingMnemonicItem(e);
			}
		}
	}

	/**
	 * The arrow at the top or bottom of a scrolled menu. Hovering over it scrolls the menu, as
	 * the hover timer of the menu checks.
	 */
	private class ScrollArrow extends JComponent {
		private final int direction;

		ScrollArrow(int direction) {
			this.direction = direction;
			setOpaque(false);
		}

		/**
		 * {@return the height of the arrow row}  It follows the menu font, so that the arrows grow
		 * with the font size; display scaling is applied by Java itself.
		 */
		private int getRowHeight() {
			Font font = UIManager.getFont("MenuItem.font");
			if (font == null) {
				font = popup.getFont();
			}
			if (font == null) {
				return MIN_ARROW_ROW_HEIGHT;
			}
			return Math.max(MIN_ARROW_ROW_HEIGHT, getFontMetrics(font).getAscent());
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(0, getRowHeight());
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Short.MAX_VALUE, getRowHeight());
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(Colors.FOREGROUND);
				// a vector shape in fractional coordinates stays sharp at every display scale
				double halfWidth = getHeight() / 3.0;
				double cx = getWidth() / 2.0;
				double cy = getHeight() / 2.0;
				double tip = cy + direction * halfWidth / 2;
				double base = cy - direction * halfWidth / 2;
				Path2D.Double arrow = new Path2D.Double();
				arrow.moveTo(cx - halfWidth, base);
				arrow.lineTo(cx + halfWidth, base);
				arrow.lineTo(cx, tip);
				arrow.closePath();
				g2.fill(arrow);
			}
			finally {
				g2.dispose();
			}
		}
	}
}
