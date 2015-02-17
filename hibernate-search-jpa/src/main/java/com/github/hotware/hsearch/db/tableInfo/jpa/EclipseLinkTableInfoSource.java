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
package com.github.hotware.hsearch.db.tableInfo.jpa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.internal.helper.DatabaseField;
import org.eclipse.persistence.internal.jpa.EntityManagerImpl;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.mappings.DirectToFieldMapping;
import org.eclipse.persistence.mappings.ManyToManyMapping;
import org.eclipse.persistence.mappings.OneToManyMapping;
import org.eclipse.persistence.mappings.OneToOneMapping;
import org.eclipse.persistence.mappings.UnidirectionalOneToManyMapping;

import com.github.hotware.hsearch.db.tableInfo.TableInfo;
import com.github.hotware.hsearch.db.tableInfo.TableInfoSource;

/**
 * @author Martin Braun
 */
public class EclipseLinkTableInfoSource implements TableInfoSource {

	private final EntityManagerImpl em;

	public EclipseLinkTableInfoSource(EntityManagerImpl em) {
		this.em = em;
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<TableInfo> getTableInfos(List<Class<?>> classesInIndex) {
		List<TableInfo> ret = new ArrayList<>();
		for (Class<?> clazz : classesInIndex) {
			ClassDescriptor classDescriptor = this.em.getSession()
					.getDescriptor(clazz);

			{
				// handle all stuff for ourself
				final List<String> primaryKeyFieldNames;
				final Map<String, Class<?>> primaryKeyColumnTypes;
				{
					primaryKeyFieldNames = new ArrayList<>();
					primaryKeyColumnTypes = new HashMap<>();
					for (DatabaseField pkField : classDescriptor
							.getPrimaryKeyFields()) {
						String idColumn = String.format("%s.%s",
								pkField.getTableName(), pkField.getName());
						primaryKeyFieldNames.add(idColumn);
						primaryKeyColumnTypes.put(idColumn, pkField.getType());
					}
				}

				final List<String> tableNames;
				{
					tableNames = new ArrayList<>();
					tableNames.addAll(classDescriptor.getTableNames());
				}

				TableInfo.IdInfo idInfo = new TableInfo.IdInfo(clazz,
						Collections.unmodifiableList(primaryKeyFieldNames),
						Collections.unmodifiableMap(primaryKeyColumnTypes));
				ret.add(new TableInfo(Collections.unmodifiableList(Arrays
						.asList(idInfo)), Collections
						.unmodifiableList(tableNames)));
			}

			// TODO: test this for ElementCollections, ...
			// maybe we will have to change some things then

			// and now for relationship tables
			for (DatabaseMapping mapping : classDescriptor.getMappings()) {
				if (mapping instanceof ManyToManyMapping
						|| mapping instanceof OneToOneMapping) {
					final List<DatabaseField> sourceRelationKeyFields;
					final List<DatabaseField> sourceKeyFields;
					final List<DatabaseField> targetRelationKeyFields;
					final List<DatabaseField> targetKeyFields;
					final String relationTableName;
					final Class<?> referenceClass;
					{
						if (mapping instanceof OneToOneMapping) {
							// this handles ManyToOneMapping as well
							OneToOneMapping oto = (OneToOneMapping) mapping;
							// only if this thing is mapped in a relation
							// table we need
							// information since otherwhise this is already
							// done via
							// mapping the original table(s)
							if (oto.getRelationTableMechanism() == null) {
								continue;
							}
							sourceRelationKeyFields = oto
									.getRelationTableMechanism()
									.getSourceRelationKeyFields();
							sourceKeyFields = oto.getRelationTableMechanism()
									.getSourceKeyFields();
							targetRelationKeyFields = oto
									.getRelationTableMechanism()
									.getTargetRelationKeyFields();
							targetKeyFields = oto.getRelationTableMechanism()
									.getTargetKeyFields();
							relationTableName = oto.getRelationTableMechanism()
									.getRelationTableName();
							referenceClass = oto.getReferenceClass();
						} else {
							ManyToManyMapping mtm = (ManyToManyMapping) mapping;
							sourceRelationKeyFields = mtm
									.getRelationTableMechanism()
									.getSourceRelationKeyFields();
							sourceKeyFields = mtm.getRelationTableMechanism()
									.getSourceKeyFields();
							targetRelationKeyFields = mtm
									.getRelationTableMechanism()
									.getTargetRelationKeyFields();
							targetKeyFields = mtm.getRelationTableMechanism()
									.getTargetKeyFields();
							relationTableName = mtm.getRelationTableMechanism()
									.getRelationTableName();
							referenceClass = mtm.getReferenceClass();
						}
					}
					// ManyToManyMapping mtm = (ManyToManyMapping) mapping;
					final TableInfo.IdInfo toThis;
					{
						List<String> ownForeignKeyColumns = new ArrayList<>();
						Map<String, Class<?>> ownForeignKeyColumnTypes = new HashMap<>();
						for (int i = 0; i < sourceRelationKeyFields.size(); ++i) {
							DatabaseField ownFkField = sourceRelationKeyFields
									.get(i);
							String idColumn = ownFkField.getName();
							ownForeignKeyColumns.add(idColumn);
							ownForeignKeyColumnTypes.put(idColumn,
									sourceKeyFields.get(i).getType());
						}
						toThis = new TableInfo.IdInfo(
								clazz,
								Collections
										.unmodifiableList(ownForeignKeyColumns),
								Collections
										.unmodifiableMap(ownForeignKeyColumnTypes));
					}
					final TableInfo.IdInfo toOtherEnd;
					{
						List<String> otherForeignKeyColumns = new ArrayList<>();
						Map<String, Class<?>> otherForeignKeyColumnTypes = new HashMap<>();
						for (int i = 0; i < targetRelationKeyFields.size(); ++i) {
							DatabaseField otherFkField = targetRelationKeyFields
									.get(i);
							String idColumn = otherFkField.getName();
							otherForeignKeyColumns.add(idColumn);
							otherForeignKeyColumnTypes.put(idColumn,
									targetKeyFields.get(i).getType());
						}
						toOtherEnd = new TableInfo.IdInfo(
								referenceClass,
								Collections
										.unmodifiableList(otherForeignKeyColumns),
								Collections
										.unmodifiableMap(otherForeignKeyColumnTypes));
					}
					ret.add(new TableInfo(Collections.unmodifiableList(Arrays
							.asList(toThis, toOtherEnd)), Collections
							.unmodifiableList(Arrays.asList(relationTableName))));

				} else if (mapping instanceof OneToManyMapping) {
					// this should be fine. OneToManyMapping(s) are only used
					// when the target already contain the foreign keys
					// and we get the updateinformation needed for these
					// by parsing these classes itself
				} else if (mapping instanceof DirectToFieldMapping) {
					// obviously fine
				} else {
					throw new IllegalArgumentException(
							mapping.getClass()
									+ " found. only OneToOne, ManyToOne, OneToMany or ManyToMany allowed, yet!");
				}
			}
		}
		return ret;
	}
}