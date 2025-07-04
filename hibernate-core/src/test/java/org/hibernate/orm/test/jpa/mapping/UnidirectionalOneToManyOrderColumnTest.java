/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.jpa.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.hibernate.testing.orm.junit.EntityManagerFactoryScope;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.Jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@JiraKey(value = "HHH-11587")
@Jpa(annotatedClasses = {
		UnidirectionalOneToManyOrderColumnTest.ParentData.class,
		UnidirectionalOneToManyOrderColumnTest.ChildData.class
})
public class UnidirectionalOneToManyOrderColumnTest {

	@AfterEach
	public void tearDown(EntityManagerFactoryScope scope) {
		scope.getEntityManagerFactory().getSchemaManager().truncate();
	}

	@Test
	public void testRemovingAnElement(EntityManagerFactoryScope scope) {
		long parentId = scope.fromTransaction(
				entityManager -> {
					ParentData parent = new ParentData();
					entityManager.persist( parent );

					String[] childrenStr = new String[] {"One", "Two", "Three"};
					for ( String str : childrenStr ) {
						ChildData child = new ChildData( str );
						entityManager.persist( child );
						parent.getChildren().add( child );
					}

					entityManager.flush();

					List<ChildData> children = parent.getChildren();
					children.remove( 0 );
					return parent.id;
				}
		);

		scope.inEntityManager(
				entityManager -> {
					ParentData parent = entityManager.find( ParentData.class, parentId );
					List<String> childIds = parent.getChildren().stream().map( ChildData::toString ).collect( Collectors.toList() );
					int i = 0;
					assertEquals( "Two", childIds.get( i++ ));
					assertEquals( "Three", childIds.get( i ));
				}
		);
	}

	@Test
	public void testAddingAnElement(EntityManagerFactoryScope scope) {
		long parentId = scope.fromTransaction(
				entityManager -> {
					ParentData parent = new ParentData();
					entityManager.persist( parent );

					String[] childrenStr = new String[] {"One", "Two", "Three"};
					for ( String str : childrenStr ) {
						ChildData child = new ChildData( str );
						entityManager.persist( child );
						parent.getChildren().add( child );
					}

					entityManager.flush();

					List<ChildData> children = parent.getChildren();
					children.add( 1, new ChildData( "Another" ) );
					return parent.id;
				}
		);

		scope.inEntityManager(
				entityManager -> {
					ParentData parent = entityManager.find( ParentData.class, parentId );
					List<String> childIds = parent.getChildren().stream().map( ChildData::toString ).collect( Collectors.toList() );
					int i = 0;
					assertEquals( "One", childIds.get( i++ ));
					assertEquals( "Another", childIds.get( i++ ));
					assertEquals( "Two", childIds.get( i++ ));
					assertEquals( "Three", childIds.get( i ));
				}
		);
	}

	@Test
	public void testRemovingAndAddingAnElement(EntityManagerFactoryScope scope) {
		long parentId = scope.fromTransaction(
				entityManager -> {
					ParentData parent = new ParentData();
					entityManager.persist( parent );

					String[] childrenStr = new String[] {"One", "Two", "Three"};
					for ( String str : childrenStr ) {
						ChildData child = new ChildData( str );
						entityManager.persist( child );
						parent.getChildren().add( child );
					}

					entityManager.flush();

					List<ChildData> children = parent.getChildren();
					children.remove( 0 );
					children.add( 1, new ChildData( "Another" ) );
					return parent.id;
				}
		);

		scope.inEntityManager(
				entityManager -> {
					ParentData parent = entityManager.find( ParentData.class, parentId );
					List<String> childIds = parent.getChildren().stream().map( ChildData::toString ).collect( Collectors.toList() );
					int i = 0;
					assertEquals( "Two", childIds.get( i++ ));
					assertEquals( "Another", childIds.get( i++ ));
					assertEquals( "Three", childIds.get( i ));
				}
		);
	}

	@Test
	public void testRemovingOneAndAddingTwoElements(EntityManagerFactoryScope scope) {
		long parentId = scope.fromTransaction(
				entityManager -> {
					ParentData parent = new ParentData();
					entityManager.persist( parent );

					String[] childrenStr = new String[] {"One", "Two", "Three"};
					for ( String str : childrenStr ) {
						ChildData child = new ChildData( str );
						entityManager.persist( child );
						parent.getChildren().add( child );
					}

					entityManager.flush();

					List<ChildData> children = parent.getChildren();
					children.remove( 0 );
					children.add( 1, new ChildData( "Another" ) );
					children.add( new ChildData( "Another Another" ) );
					return parent.id;
				}
		);

		scope.inEntityManager(
				entityManager -> {
					ParentData parent = entityManager.find( ParentData.class, parentId );
					List<String> childIds = parent.getChildren().stream().map( ChildData::toString ).collect( Collectors.toList() );
					int i = 0;
					assertEquals( "Two", childIds.get( i++ ));
					assertEquals( "Another", childIds.get( i++ ));
					assertEquals( "Three", childIds.get( i++ ));
					assertEquals( "Another Another", childIds.get( i ));
				}
		);
	}

	@Entity(name = "ParentData")
	@Table(name = "PARENT")
	public static class ParentData {
		@Id
		@GeneratedValue
		long id;

		@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
		@OrderColumn(name = "listOrder")
		private List<ChildData> children = new ArrayList<>();

		public List<ChildData> getChildren() {
			return children;
		}
	}

	@Entity(name = "ChildData")
	@Table(name = "CHILD")
	public static class ChildData {
		@Id
		@GeneratedValue
		long id;

		String childId;

		public ChildData() {
		}

		public ChildData(String id) {
			childId = id;
		}

		@Override
		public String toString() {
			return childId;
		}
	}

}
