/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.mapping.collections.classification.list;/**
 * @author Steve Ebersole
 */

import java.util.List;

import org.hibernate.annotations.ListIndexBase;
import org.hibernate.orm.test.mapping.collections.classification.Name;

import jakarta.persistence.Basic;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;

/**
 * @author Steve Ebersole
 */ //tag::collections-list-indexbase-ex[]
@Entity
public class EntityWithIndexBasedList {
	// ...
	//end::collections-list-indexbase-ex[]

	@Id
	private Integer id;
	@Basic
	private String name;

	//tag::collections-list-indexbase-ex[]
	@ElementCollection
	@OrderColumn(name = "name_index")
	@ListIndexBase(1)
	private List<Name> names;
	//end::collections-list-indexbase-ex[]

	private EntityWithIndexBasedList() {
		// for Hibernate use
	}

	public EntityWithIndexBasedList(Integer id, String name) {
		this.id = id;
		this.name = name;
	}

	public Integer getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	//tag::collections-list-indexbase-ex[]
}
//end::collections-list-indexbase-ex[]
