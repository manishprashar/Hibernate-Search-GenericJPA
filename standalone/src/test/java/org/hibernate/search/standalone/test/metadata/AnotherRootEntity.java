/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.standalone.test.metadata;

import org.hibernate.search.annotations.Analyze;
import org.hibernate.search.annotations.DocumentId;
import org.hibernate.search.annotations.Field;
import org.hibernate.search.annotations.Indexed;
import org.hibernate.search.annotations.IndexedEmbedded;

/**
 * @author Martin Braun
 */
@Indexed
public class AnotherRootEntity {

	@DocumentId
	private Long id;

	@Field(analyze = Analyze.NO)
	private String name;

	@IndexedEmbedded(prefix = "otherEntityFromAnotherRoot.", includeEmbeddedObjectId = true)
	private SubEntity someSubEntity;

}