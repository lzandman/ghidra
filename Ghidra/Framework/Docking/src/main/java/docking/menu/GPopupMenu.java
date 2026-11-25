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

import java.awt.Point;

import javax.swing.JPopupMenu;

/**
 * A drop-in replacement for {@link JPopupMenu} that scrolls when it is taller than the screen.
 */
public class GPopupMenu extends JPopupMenu {

	private Point requestedLocation;

	public GPopupMenu() {
		super();
		MenuScroller.install(this);
	}

	public GPopupMenu(String label) {
		super(label);
		MenuScroller.install(this);
	}

	@Override
	public void setLocation(int x, int y) {
		requestedLocation = new Point(x, y);
		super.setLocation(x, y);
	}

	/**
	 * {@return the screen location last asked for, before Swing moves the menu onto the screen;
	 * null if none}
	 */
	Point getRequestedLocation() {
		return requestedLocation == null ? null : new Point(requestedLocation);
	}
}
