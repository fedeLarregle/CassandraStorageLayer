package org.hcjf.layers.storage.cassandra.actions;

import org.hcjf.layers.storage.StorageAccessException;
import org.hcjf.layers.storage.actions.*;
import org.hcjf.layers.storage.cassandra.CassandraStorageSession;
import org.hcjf.layers.storage.cassandra.properties.CassandraProperties;
import org.hcjf.log.Log;
import org.hcjf.properties.SystemProperties;
import org.hcjf.utils.Introspection;
import org.hcjf.utils.Strings;
import sun.swing.BakedArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class implements the update operation for cassandra.
 * @author javaito
 * @mail javaito@gmail.com
 */
public class CassandraUpdate extends Update<CassandraStorageSession> {

    private static final String UPDATE_STATEMENT = "UPDATE %s SET %s WHERE %s";

    public CassandraUpdate(CassandraStorageSession session) {
        super(session);
    }

    /**
     * This method first execute a query to found all the rows to delete then creates a single
     * update operation foreach row and execute this operations.
     * @param params Query parameters
     * @param <R> Expected result set.
     * @return Return the result set with all the rows deleted.
     * @throws StorageAccessException
     */
    @Override
    public <R extends ResultSet> R execute(Object... params) throws StorageAccessException {
        R resultSet;

        //Make cassandra select
        Select select = getSession().select(getQuery());
        MapResultSet selectResultSet = (MapResultSet) select.execute(params);

        String resourceName;
        if(getResultType() != null) {
            resourceName = getSession().normalizeName(getResultType().getSimpleName());
        } else if(getResourceName() != null) {
            resourceName = getSession().normalizeName(getResourceName());
        } else {
            throw new StorageAccessException("Resource name not found");
        }

        //Obtains the partitions keys and clustering keys for the specific resource.
        List<String> keys = getSession().getPartitionKey(getSession().normalizeName(resourceName));
        keys.addAll(getSession().getClusteringKey(getSession().normalizeName(resourceName)));

        //List of values for all updates
        List<Object> baseValues = new ArrayList<>();
        //Creates the assignations body of the update operation.
        Strings.Builder setBuilder = new Strings.Builder();
        for(String fieldName : getValues().keySet()) {
            setBuilder.append(getSession().normalizeName(fieldName)).append(Strings.ASSIGNATION).append(Strings.WHITE_SPACE);
            setBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.REPLACEABLE_VALUE), Strings.ARGUMENT_SEPARATOR, Strings.WHITE_SPACE);
            baseValues.add(getSession().checkUpdateValue(getValues().get(fieldName).getValue()));
        }

        //Creates the conditions body of the update operation.
        Strings.Builder whereBuilder = new Strings.Builder();
        for(String key : keys) {
            whereBuilder.append(key).append(SystemProperties.get(SystemProperties.Query.ReservedWord.EQUALS));
            whereBuilder.append(Strings.WHITE_SPACE).append(SystemProperties.get(SystemProperties.Query.ReservedWord.REPLACEABLE_VALUE));
            whereBuilder.append(Strings.WHITE_SPACE, SystemProperties.get(SystemProperties.Query.ReservedWord.AND));
        }

        //Creates statement string
        String statement = String.format(UPDATE_STATEMENT, resourceName, setBuilder.toString(), whereBuilder.toString());

        //Creates a list to store the values for the delete statement.
        List<Object> values = new ArrayList<>();

        //These collections are for store the deleted objects.
        List<Object> resultCollection = getResultType() != null ? new ArrayList<>() : null;
        List<Map<String, Object>> resultMap = getResultType() == null ? new ArrayList<>() : null;
        for (Map<String, Object> row : selectResultSet.getResult()) {
            try {
                //The values list is cleared for each row then put in the list the current row values.
                values.clear();
                values.addAll(baseValues);
                for (String key : keys) {
                    values.add(row.get(getSession().normalizeName(key)));
                }

                //Execute the delete statement.
                getSession().execute(statement, values, getResultType());

                //If the expected type is a specific object then creates an instance foreach row and put it into
                //the result list.
                if (getResultType() != null) {
                    resultCollection.add(Introspection.toInstance(row, getResultType()));
                } else {
                    resultMap.add(row);
                }
            } catch (Exception ex){
                Log.w(SystemProperties.get(CassandraProperties.CASSADNRA_STORAGE_LAYER_LOG_TAG),
                        "Unable to update row %s", row.toString());
            }
        }

        if(getResultType() != null) {
            resultSet = (R) new CollectionResultSet(resultCollection);
        } else {
            resultSet = (R) new MapResultSet(resultMap);
        }

        return resultSet;
    }
}
