/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.mapping.embeddable.strategy.usertype.registered;

import org.hibernate.orm.test.mapping.embeddable.strategy.usertype.embedded.Name;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DomainModel( annotatedClasses = { Person.class, Name.class } )
@SessionFactory
public class CompositeUserTypeTests {

	@Test
	public void basicTest(SessionFactoryScope scope) {
		scope.inTransaction( (session) -> {
			final Person mick = new Person( 1, new Name( "Mick", "Jagger" ) );
			session.persist( mick );

			final Person john = new Person( 2, new Name( "John", "Doe" ) );
			john.addAlias( new Name( "Jon", "Doe" ) );
			session.persist( john );
		} );
		scope.inTransaction( (session) -> {
			final Person mick = session.createQuery( "from Person where id = 1", Person.class ).uniqueResult();
			assertThat( mick.getName().firstName() ).isEqualTo( "Mick" );
		} );
		scope.inTransaction( (session) -> {
			final Person john = session.createQuery( "from Person p join fetch p.aliases where p.id = 2", Person.class ).uniqueResult();
			assertThat( john.getName().firstName() ).isEqualTo( "John" );
			assertThat( john.getAliases() ).hasSize( 1 );
			final Name alias = john.getAliases().iterator().next();
			assertThat( alias.firstName() ).isEqualTo( "Jon" );
		} );
	}
}
