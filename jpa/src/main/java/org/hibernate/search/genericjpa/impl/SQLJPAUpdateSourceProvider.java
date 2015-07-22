/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.genericjpa.impl;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.hibernate.search.genericjpa.db.events.impl.AnnotationEventModelParser;
import org.hibernate.search.genericjpa.db.events.impl.EventModelInfo;
import org.hibernate.search.genericjpa.db.events.impl.EventModelParser;
import org.hibernate.search.genericjpa.db.events.EventType;
import org.hibernate.search.genericjpa.db.events.UpdateSource;
import org.hibernate.search.genericjpa.db.events.jpa.impl.JPAUpdateSource;
import org.hibernate.search.genericjpa.db.events.triggers.TriggerSQLStringSource;
import org.hibernate.search.genericjpa.exception.SearchException;
import org.hibernate.search.genericjpa.jpa.util.JPATransactionWrapper;

import static org.hibernate.search.genericjpa.Constants.BATCH_SIZE_FOR_UPDATE_QUERIES_DEFAULT_VALUE;
import static org.hibernate.search.genericjpa.Constants.BATCH_SIZE_FOR_UPDATE_QUERIES_KEY;
import static org.hibernate.search.genericjpa.Constants.TRIGGER_CREATION_STRATEGY_CREATE;
import static org.hibernate.search.genericjpa.Constants.TRIGGER_CREATION_STRATEGY_DONT_CREATE;
import static org.hibernate.search.genericjpa.Constants.TRIGGER_CREATION_STRATEGY_DROP_CREATE;

/**
 * @author Martin Braun
 */
public class SQLJPAUpdateSourceProvider implements UpdateSourceProvider {

	private static final Logger LOGGER = Logger.getLogger( SQLJPAUpdateSourceProvider.class.getName() );

	private final TriggerSQLStringSource triggerSource;
	private final List<Class<?>> updateClasses;
	private final EntityManagerFactory emf;
	private final TransactionManager transactionManager;
	private final String triggerCreateStrategy;

	public SQLJPAUpdateSourceProvider(
			EntityManagerFactory emf,
			TransactionManager transactionManager,
			TriggerSQLStringSource triggerSource,
			List<Class<?>> updateClasses,
			String triggerCreateStrategy) {
		this.triggerSource = triggerSource;
		this.updateClasses = updateClasses;
		this.emf = emf;
		this.transactionManager = transactionManager;
		this.triggerCreateStrategy = triggerCreateStrategy;
	}

	@Override
	public UpdateSource getUpdateSource(long delay, TimeUnit timeUnit, int batchSizeForUpdates, Properties properties) {
		EventModelParser eventModelParser = new AnnotationEventModelParser();
		List<EventModelInfo> eventModelInfos = eventModelParser.parse( new ArrayList<>( this.updateClasses ) );
		this.setupTriggers( eventModelInfos );
		return new JPAUpdateSource(
				eventModelInfos,
				this.emf,
				this.transactionManager,
				delay,
				timeUnit,
				batchSizeForUpdates,
				Integer.parseInt(
						properties.getProperty(
								BATCH_SIZE_FOR_UPDATE_QUERIES_KEY,
								BATCH_SIZE_FOR_UPDATE_QUERIES_DEFAULT_VALUE
						)
				), this.triggerSource.getDelimitedIdentifierToken()
		);
	}

	private void setupTriggers(List<EventModelInfo> eventModelInfos) {
		if ( TRIGGER_CREATION_STRATEGY_DONT_CREATE.equals( this.triggerCreateStrategy ) || (!TRIGGER_CREATION_STRATEGY_CREATE
				.equals( this.triggerCreateStrategy ) && !TRIGGER_CREATION_STRATEGY_DROP_CREATE.equals( this.triggerCreateStrategy )) ) {
			return;
		}
		try {
			for ( EventModelInfo info : eventModelInfos ) {
				if ( TRIGGER_CREATION_STRATEGY_DROP_CREATE.equals( this.triggerCreateStrategy ) ) {
					for ( String str : triggerSource.getUpdateTableDropCode( info ) ) {
						LOGGER.info( str );
						this.doQueryOrLogException( str );
					}
				}

				for ( String str : triggerSource.getUpdateTableCreationCode( info ) ) {
					LOGGER.info( str );
					this.doQueryOrLogException( str );
				}
			}

			try {
				if ( TRIGGER_CREATION_STRATEGY_DROP_CREATE.equals( this.triggerCreateStrategy ) ) {
					for ( String str : triggerSource.getUnSetupCode() ) {
						LOGGER.info( str );
						this.doQueryOrLogException( str );
					}
				}
				for ( String str : triggerSource.getSetupCode() ) {
					LOGGER.info( str );
					this.doQueryOrLogException( str );
				}

				for ( EventModelInfo info : eventModelInfos ) {
					if ( TRIGGER_CREATION_STRATEGY_DROP_CREATE.equals( this.triggerCreateStrategy ) ) {
						for ( String unSetupCode : this.triggerSource.getSpecificUnSetupCode( info ) ) {
							LOGGER.info( unSetupCode );
							this.doQueryOrLogException( unSetupCode );
						}
					}

					for ( String setupCode : this.triggerSource.getSpecificSetupCode( info ) ) {
						LOGGER.info( setupCode );
						this.doQueryOrLogException( setupCode );
					}

					if ( TRIGGER_CREATION_STRATEGY_DROP_CREATE.equals( this.triggerCreateStrategy ) ) {
						for ( int eventType : EventType.values() ) {
							String[] triggerDropStrings = this.triggerSource.getTriggerDropCode( info, eventType );
							for ( String triggerDropString : triggerDropStrings ) {
								LOGGER.info( triggerDropString );
								this.doQueryOrLogException( triggerDropString );
							}
						}
					}

					for ( int eventType : EventType.values() ) {
						String[] triggerCreationStrings = this.triggerSource.getTriggerCreationCode( info, eventType );
						for ( String triggerCreationString : triggerCreationStrings ) {
							LOGGER.info( triggerCreationString );
							this.doQueryOrLogException( triggerCreationString );
						}
					}
				}
			}
			catch (Exception e) {
				throw new SearchException( e );
			}
			LOGGER.info( "finished setting up triggers!" );
		}
		catch (SecurityException e) {
			throw new SearchException( e );
		}
	}

	private void doQueryOrLogException(String query) {
		EntityManager em = null;
		try {
			em = this.emf.createEntityManager();

			JPATransactionWrapper tx = JPATransactionWrapper.get( em, this.transactionManager );
			tx.setIgnoreExceptionsForJTATransaction( true );
			tx.begin();

			em.createNativeQuery( query ).executeUpdate();

			tx.commitIgnoreExceptions();
		}
		//FIXME: better message. maybe we should do this in our own connection...
		catch (Exception e) {
			LOGGER.warning(
					"Exception occured during setup of triggers (most of the time, this is okay): " +
							e.getCause().getMessage()
			);
		}
		finally {
			if ( em != null ) {
				em.close();
			}
		}
	}

}
