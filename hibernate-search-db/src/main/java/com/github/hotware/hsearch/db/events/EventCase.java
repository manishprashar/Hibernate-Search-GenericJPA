/*
 * Copyright 2015 Martin Braun
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.hotware.hsearch.db.events;

/**
 * @author Martin
 *
 */
public final class EventCase {
	
	private EventCase() {
		throw new AssertionError("can't touch this!");
	}

	public static final int DELETE = -1;
	public static final int UPDATE = -2;
	public static final int INSERT = -3;
	
}