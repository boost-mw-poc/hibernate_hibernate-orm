/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.processor.test.hhh17661;

import org.hibernate.processor.test.util.CompilationTest;
import org.hibernate.processor.test.util.TestForIssue;
import org.hibernate.processor.test.util.TestUtil;
import org.hibernate.processor.test.util.WithClasses;
import org.junit.jupiter.api.Test;

@CompilationTest
@TestForIssue(jiraKey = " HHH-17661")
class HHH17661Test {

	@Test
	@WithClasses({ Entity.class, Tree.class, TreeRelation.class })
	@TestForIssue(jiraKey = " HHH-17661")
	void test() {
		System.out.println( TestUtil.getMetaModelSourceAsString( Entity.class ) );
		System.out.println( TestUtil.getMetaModelSourceAsString( Tree.class ) );
		System.out.println( TestUtil.getMetaModelSourceAsString( TreeRelation.class ) );
	}
}
