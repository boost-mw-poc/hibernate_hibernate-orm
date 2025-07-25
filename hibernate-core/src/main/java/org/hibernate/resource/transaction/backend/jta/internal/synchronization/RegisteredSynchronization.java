/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.resource.transaction.backend.jta.internal.synchronization;

import jakarta.transaction.Synchronization;


import static org.hibernate.resource.transaction.backend.jta.internal.JtaLogging.JTA_LOGGER;

/**
 * The JTA {@link jakarta.transaction.Synchronization} Hibernate registers when needed for JTA callbacks.
 * <p>
 * Note that we split the notion of the registered Synchronization and the processing of the Synchronization callbacks
 * mainly to account for "separation of concerns", but also so that the transaction engine does not have to hold
 * reference to the actual Synchronization that gets registered with the JTA system.
 *
 * @author Steve Ebersole
 */
public class RegisteredSynchronization implements Synchronization {

	private final SynchronizationCallbackCoordinator synchronizationCallbackCoordinator;

	public RegisteredSynchronization(SynchronizationCallbackCoordinator synchronizationCallbackCoordinator) {
		this.synchronizationCallbackCoordinator = synchronizationCallbackCoordinator;
	}

	@Override
	public void beforeCompletion() {
		JTA_LOGGER.trace( "Registered JTA Synchronization: beforeCompletion()" );

		synchronizationCallbackCoordinator.beforeCompletion();
	}

	@Override
	public void afterCompletion(int status) {
		JTA_LOGGER.tracef( "Registered JTA Synchronization: afterCompletion(%s)", status );

		synchronizationCallbackCoordinator.afterCompletion( status );
	}
}
