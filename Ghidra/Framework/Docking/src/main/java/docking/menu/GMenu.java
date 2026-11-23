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

import javax.swing.JMenu;

/**
 * A drop-in replacement for {@link JMenu} whose menu scrolls when it is taller than the screen.
 */
public class GMenu extends JMenu {

	public GMenu() {
		super();
		MenuScroller.install(getPopupMenu());
	}

	public GMenu(String text) {
		super(text);
		MenuScroller.install(getPopupMenu());
	}
}
