/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.genericjpa;

import javax.persistence.EntityManagerFactory;
import javax.persistence.metamodel.EntityType;
import javax.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.hibernate.search.annotations.Indexed;
import org.hibernate.search.genericjpa.annotations.InIndex;
import org.hibernate.search.genericjpa.annotations.Updates;
import org.hibernate.search.genericjpa.db.events.TriggerSQLStringSource;
import org.hibernate.search.genericjpa.exception.SearchException;
import org.hibernate.search.genericjpa.impl.JPASearchFactoryAdapter;
import org.hibernate.search.genericjpa.impl.SQLJPAUpdateSourceProvider;
import org.hibernate.search.genericjpa.impl.SearchFactoryRegistry;
import org.hibernate.search.genericjpa.impl.UpdateSourceProvider;
import org.hibernate.search.genericjpa.transaction.TransactionManagerProvider;

import static org.hibernate.search.genericjpa.Constants.ADDITIONAL_INDEXED_TYPES_KEY;
import static org.hibernate.search.genericjpa.Constants.BATCH_SIZE_FOR_UPDATES_DEFAULT_VALUE;
import static org.hibernate.search.genericjpa.Constants.BATCH_SIZE_FOR_UPDATES_KEY;
import static org.hibernate.search.genericjpa.Constants.SEARCH_FACTORY_TYPE_DEFAULT_VALUE;
import static org.hibernate.search.genericjpa.Constants.SEARCH_FACTORY_TYPE_KEY;
import static org.hibernate.search.genericjpa.Constants.TRANSACTION_MANAGER_PROVIDER_DEFAULT_VALUE;
import static org.hibernate.search.genericjpa.Constants.TRANSACTION_MANAGER_PROVIDER_KEY;
import static org.hibernate.search.genericjpa.Constants.TRIGGER_SOURCE_KEY;
import static org.hibernate.search.genericjpa.Constants.UPDATE_DELAY_DEFAULT_VALUE;
import static org.hibernate.search.genericjpa.Constants.UPDATE_DELAY_KEY;
import static org.hibernate.search.genericjpa.Constants.USE_JTA_TRANSACTIONS_DEFAULT_VALUE;
import static org.hibernate.search.genericjpa.Constants.USE_JTA_TRANSACTIONS_KEY;

/**
 * @author Martin Braun
 */
public final class Setup {

	private static final Logger LOGGER = Logger.getLogger( Setup.class.getName() );

	private Setup() {
		// can't touch this!
	}

	public static JPASearchFactoryController createSearchFactory(EntityManagerFactory emf) {
		return createSearchFactory( emf, emf.getProperties() );
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static JPASearchFactoryController createSearchFactory(EntityManagerFactory emf, Map properties) {
		try {
			LOGGER.info( "using hibernate-search properties: " + properties );

			// hack... but OpenJPA wants this so it can enhance the classes.
			emf.createEntityManager().close();

			// get all the root types marked by an @InIndex and @Indexed (@Indexed isn't sufficient here!)
			List<Class<?>> indexRootTypes = new ArrayList<>();
			emf.getMetamodel().getEntities().stream().map( EntityType::getBindableJavaType ).filter(
					(entityClass) -> entityClass.isAnnotationPresent( InIndex.class ) && entityClass.isAnnotationPresent(
							Indexed.class
					)
			).forEach(
					indexRootTypes::add
			);
			// user specified types are supported. even those that are no JPA entities!
			String additionalIndexedTypesValue = (String) properties.get( ADDITIONAL_INDEXED_TYPES_KEY );
			if ( additionalIndexedTypesValue != null ) {
				for ( String entityClassName : additionalIndexedTypesValue.split( "," ) ) {
					entityClassName = entityClassName.trim();
					LOGGER.info( "using additional indexed type: " + entityClassName );
					Class<?> entityClass = Class.forName( entityClassName );
					indexRootTypes.add( entityClass );
				}
			}

			// get the basic properties
			String name = SearchFactoryRegistry.getNameProperty( properties );
			if ( SearchFactoryRegistry.getSearchFactory( name ) != null ) {
				throw new SearchException( "there is already a searchfactory running for name: " + name + ". close it first!" );
			}


			boolean useJTATransactions = Boolean.parseBoolean(
					(String) properties.getOrDefault(
							USE_JTA_TRANSACTIONS_KEY,
							USE_JTA_TRANSACTIONS_DEFAULT_VALUE
					)
			);
			TransactionManager transactionManager = null;
			if ( useJTATransactions ) {
				LOGGER.info( "using JTA Transactions" );
				String transactionManagerClassName = (String) properties.getOrDefault(
						TRANSACTION_MANAGER_PROVIDER_KEY,
						TRANSACTION_MANAGER_PROVIDER_DEFAULT_VALUE
				);
				if ( transactionManagerClassName == null ) {
					throw new SearchException( TRANSACTION_MANAGER_PROVIDER_KEY + " must be specified when using JTA transactions!" );
				}
				//FIXME: can we do this differently?
				//we have to make sure that this method is called on the right thread/classloader, which is not ideal
				Class<TransactionManagerProvider> providerClass = (Class<TransactionManagerProvider>) Class.forName(
						transactionManagerClassName
				);
				transactionManager = providerClass.newInstance().get(
						Thread.currentThread().getContextClassLoader(),
						properties
				);
			}

			String type = (String) properties.getOrDefault(
					SEARCH_FACTORY_TYPE_KEY,
					SEARCH_FACTORY_TYPE_DEFAULT_VALUE
			);
			// get all the updates classes marked by an @Updates annotation
			List<Class<?>> updateClasses = emf.getMetamodel().getEntities().stream().map(
					EntityType::getBindableJavaType
			).filter(
					(entityClass) -> entityClass.isAnnotationPresent( Updates.class )
			).collect( Collectors.toList() );
			//what UpdateSource to be used
			UpdateSourceProvider updateSourceProvider;
			if ( "sql".equals( type ) ) {
				String triggerSource = (String) properties.get( TRIGGER_SOURCE_KEY );
				Class<?> triggerSourceClass;
				if ( triggerSource == null || (triggerSourceClass = Class.forName( triggerSource )) == null ) {
					throw new SearchException(
							"class specified in " + TRIGGER_SOURCE_KEY + " could not be found."
					);
				}
				updateSourceProvider = new SQLJPAUpdateSourceProvider(
						emf, transactionManager, (TriggerSQLStringSource) triggerSourceClass.newInstance(),
						updateClasses
				);
			}
			else if ( "manual-updates".equals( type ) ) {
				updateSourceProvider = (a, b, c) -> null;
			}
			else {
				throw new SearchException( "unrecognized type : " + type );
			}

			Integer batchSizeForUpdates = Integer
					.parseInt(
							(String) properties.getOrDefault(
									BATCH_SIZE_FOR_UPDATES_KEY,
									BATCH_SIZE_FOR_UPDATES_DEFAULT_VALUE
							)
					);
			Integer updateDelay = Integer.parseInt(
					(String) properties.getOrDefault(
							UPDATE_DELAY_KEY,
							UPDATE_DELAY_DEFAULT_VALUE
					)
			);

			JPASearchFactoryAdapter ret = new JPASearchFactoryAdapter();
			ret.setName( name ).setEmf( emf ).setUseJTATransaction( useJTATransactions ).setIndexRootTypes(
					indexRootTypes
			).setProperties( properties ).setUpdateSourceProvider( updateSourceProvider ).setBatchSizeForUpdates(
					batchSizeForUpdates
			).setUpdateDelay(
					updateDelay
			).setTransactionManager( transactionManager );

			//initialize this
			ret.init();

			SearchFactoryRegistry.setup( name, ret );
			return ret;
		}

		catch (
				Exception e
				)

		{
			if ( !(e instanceof SearchException) ) {
				throw new SearchException( e );
			}
			else {
				throw (SearchException) e;
			}
		}
	}
}
