package com.example.bundle;

import com.bmc.arsys.rx.application.common.ServiceLocator;
import com.bmc.arsys.rx.services.common.DataPage;
import com.bmc.arsys.rx.services.common.DataPageQueryParameters;
import com.bmc.arsys.rx.services.common.SortByValue;
import com.bmc.arsys.rx.services.record.DataProviderMappingConfig;
import com.bmc.arsys.rx.services.record.ExternalRecordDataProvider;
import com.bmc.arsys.rx.services.record.RecordService;
import com.bmc.arsys.rx.services.record.domain.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.*;

/**
 * This class implements the main logic of a Custom Record Definition, what happens
 * when you query data for example.
 * <p>
 * IMPORTANT:
 * We set the Web Api Record Definition name in the Custom Record Definition "Description" field.
 * <p>
 * This class needs to be declared in the file MyApplication.java as a data provider:
 * registerDataProvider(new VideoGameGamesCustomRecordProvider());
 */
public class VideoGameGamesCustomRecordProvider implements ExternalRecordDataProvider {
    /**
     * Data source provider for a custom record datasource.
     * It will be displayed in Administration, when creating a new data source connection type "custom".
     */
    private static final String PROVIDER_ID = "rawg.io (Games)";
    private static final String DATAPAGEQUERY_TYPE = "com.bmc.arsys.rx.application.record.datapage.RecordInstanceDataPageQuery";
    private static RecordService recordService = null;
    private static final String WEB_API_QUERY_PARAMETER_ROOT = "GET_QUERY_PARAMETER_";
    private static final int RAWGIO_MAX_PAGE_SIZE = 25;
    private static HashMap<String, FieldDefinition<? extends StorageType>> fieldDefinitionByFieldIdList = new HashMap<>();
    private static HashMap<String, String> webApiQueryParametersList = new HashMap<>();
    private static RecordDefinition webApiRecordDefinition = null;
    private static final String RAWGIO_COUNT_FIELD_NAME = "count";
    private static String rawgioCountFieldId = null;
    private static final List<String> RAWGIO_SORTABLE_FIELDS = List.of("name", "released", "added", "created", "updated", "rating", "metacritic");

    // LMA:: TODO:: Check that we don't pile up the hashmaps with the same values over and over again
    // since it's static...

    /**
     * This method will get the Web Api and Custom Record Definitions and save some necessary
     * information.
     *
     * @param customRecordDefinitionName String, Custom Record Definition Name.
     */
    private void getWebApiRecordDefinition(String customRecordDefinitionName) {
        String webApiRecordDefinitionName = "";

        recordService = ServiceLocator.getRecordService();

        // We get the Web Api Record Definition from the Custom Record Definition "Description? field.
        // It is a workaround and it is ugly, but it should work :)
        RecordDefinition customFullRecordDefinition = recordService.getRecordDefinition(customRecordDefinitionName);
        webApiRecordDefinitionName = customFullRecordDefinition.getDescription();

        // This will be used later to map a field name to a field Id. For a Web Api Record Definition we have many
        // different information we will need later, the id (field Id) and the Web api mapping.
        // Here below the field 1 would be mapped to the JSON path results..name.
        // Tbat means that if we query on the field 1 for a qualification, we want to use the "name" (last leaf
        // from a branch).
        //  id = 1
        //  name = "Display ID"
        //  fieldMapping.externalFieldId = "results||name";
        webApiRecordDefinition = recordService.getRecordDefinition(webApiRecordDefinitionName);
        List<FieldDefinition<? extends StorageType>> fieldDefinitions = webApiRecordDefinition.getFieldDefinitions();

        for (FieldDefinition<? extends StorageType> fieldDefinition : fieldDefinitions) {
            // Mapping the field Id with its definition, will be used later in the logic.
            fieldDefinitionByFieldIdList.put(Integer.toString(fieldDefinition.getId()), fieldDefinition);

            // Mapping the Rawg.io query parameters (they begin by "GET_QUERY_PARAMETER_" in the Web Api Record Definition).
            // We just need just get the query parameter name:
            // from "GET_QUERY_PARAMETER_page" to "page" in order to have a map with:
            //  ["page"] = <fieldId>
            if (fieldDefinition.getName().startsWith(WEB_API_QUERY_PARAMETER_ROOT)) {
                webApiQueryParametersList.put(fieldDefinition.getName().replace(WEB_API_QUERY_PARAMETER_ROOT, ""), String.valueOf(fieldDefinition.getId()));
            }

            // We need the "count" field later for our logic. This count is set by the Rawg.io Rest api call
            // and is the total count of games that are matching the rest api (with or without search).
            // {
            //    "count": 101713,
            //        "next": "https://api.rawg.io/api/games?key=a6e082bd0974424b859762036fed6384&page=2&search=the-witcher-3-wild-hunt",
            //        "previous": null,
            if (fieldDefinition.getName().equals(RAWGIO_COUNT_FIELD_NAME)) {
                rawgioCountFieldId = String.valueOf(fieldDefinition.getId());
            }
        }
    }

    /**
     * Utility method that returns the fieldId corresponding to
     *
     * @param queryParameterName String, Parameter name ("page" for example)
     * @return String, fieldId corresponding to the query parameter.
     */
    private String getQueryParameterFieldId(String queryParameterName) {
        return webApiQueryParametersList.get(queryParameterName);
    }

    /**
     * As per Rawg.io, it is not possible to sort on some fields, only those are available:
     * https://api.rawg.io/docs/#tag/games
     * name, released, added, created, updated, rating, metacritic
     * This method returns true if the field is sortable.
     *
     * @param fieldName String, the Rawg.io field name.
     * @return boolean , true if the field is sortable.
     */
    private boolean isFieldSortable(String fieldName) {
        return RAWGIO_SORTABLE_FIELDS.contains(fieldName);
    }

    /**
     * Method called when we want to fetch and display values, called by a grid for example.
     * <p>
     * The grid is leveraging the "Custom" Record Definition, we take the different information (sorting, search, pagination)
     * and "convert" / transfer the query to the "Web Api" Record Definition.
     *
     * @param recordDefinitionName,    String, custom record definition name.
     * @param dataPageQueryParameters, DataPageQueryParameters, datapagequeryparameters object sent by the grid
     *                                 containing for example the pagination, search, displayed columns, filters, sorting etc...
     *                                 For example sorting by the "name" column or searching for "foo".
     * @param customRecordDefinition,  Set<DataProviderMappingConfig>, custom record definition.
     * @param noIdea,                  Set<Integer>,     it seems it's an array of fields, maybe unique Ids (?) [1, 379, 380].
     * @return DataPage, a DataPage object.
     */
    @Override
    public DataPage getDataPage(String recordDefinitionName, DataPageQueryParameters dataPageQueryParameters, Set<DataProviderMappingConfig> customRecordDefinition, Set<Integer> noIdea) {
        /**
         * Because of Rawg.io limitation (page_size of 40), we want to get two pages of 25 items
         * to build a full datapagequery (50) expected by a grid in a View.
         * Since we are limited to 40 and our grid page is 50, we'll have to cheat.
         * Since for use one page is 50 and the max for rawg.io is 40, we could request 2 pages of 25.
         * For Innovation Studio if we have:
         *  startIndex = 0, pageSize = 50 that means for Rawg.io:
         *      page = 1, page_size = 25
         *      page = 2, page_size = 25
         *  startIndex = 50, pageSize = 50 that means for Rawg.io:
         *      page = 3, page_size = 25
         *      page = 4, page_size = 25
         *  startIndex = 100, pageSize = 50 that means for Rawg.io:
         *      page = 5, page_size = 25
         *      page = 6, page_size = 25
         * Hence the formula if we use a page_size of 25:
         *  pageStart = E(startIndex / page_size) + 1;
         *  pageEnd = pageStart + 1;
         *
         * When getting the first page, we can check the "count" to see if it's worth getting another DataPage.
         * Once we get both DataPages, we can "merge" them and send it to the UI... 
         */
        getWebApiRecordDefinition(recordDefinitionName);
        List<Object> fullDataPages = new ArrayList<>();

        /**
         * For some reason, when trying to get a record instance:
         *  http://server:port/api/rx/application/record/recordinstance/{Custom Record Definition}/{record instance Id}
         *  the Platform calls the method getDataPage() instead of getRecordInstance()...
         *  Which means that we cannot return a RecordInstance Object...
         *  In this case we have something like:
         *  pageSize is 1, startIndex is 0, shouldIncludeTotalSize is false and:
         *  dataPageQueryParameters.queryPredicatesByName().get("queryExpression") contains the value to search:
         *  '379' = "{record instance Id}"
         */

        String recordInstanceId = null;

        boolean isRecordInstanceQuery = !dataPageQueryParameters.shouldIncludeTotalSize()
                && dataPageQueryParameters.getPageSize() == 1
                && dataPageQueryParameters.getStartIndex() == 0
                && dataPageQueryParameters.getQueryPredicatesByName().get("queryExpression").getRightOperand().startsWith("'379' = ");

        if (isRecordInstanceQuery) {
            recordInstanceId = dataPageQueryParameters.getQueryPredicatesByName().get("queryExpression").getRightOperand().replace("'379' = ", "");
            recordInstanceId = recordInstanceId.replace("\"", "");
        }

        /**
         * When clicking on the 50+ link on the grid, the UI will ask for the total number of records.
         *  For some reason the method "getRecordInstanceCount()" is not called...
         *  The Platform will only send one entry back, setting the total number of records.
         *  However, in the case of a Web Api call, the Platform returns the size of the returned data, so 1.
         *  We take care of the "real" total size in the getWebApiDataPage() method.
         *  When the UI requires the count:
         *  pageSize is 1, startIndex is 0 and shouldIncludeTotalSize is true.
         */
        boolean isRequestForCountOnly = dataPageQueryParameters.shouldIncludeTotalSize()
                && dataPageQueryParameters.getPageSize() == 1
                && dataPageQueryParameters.getStartIndex() == 0;

        int firstPageId = isRequestForCountOnly || isRecordInstanceQuery ? 1 : (dataPageQueryParameters.getStartIndex() / RAWGIO_MAX_PAGE_SIZE) + 1;
        DataPage firstDataPage = getWebApiDataPage(firstPageId, dataPageQueryParameters, recordInstanceId);

        fullDataPages.addAll(firstDataPage.getData());

        // Due to the paging limitation from Rawg.io explained earlier, we might only get 25 records, when
        // Innovation Studio expects 50 for a grid. In this case, we might need to perform an additional call
        // to get those 50 items.
        if (!isRequestForCountOnly && !isRecordInstanceQuery && firstDataPage.getData().size() >= RAWGIO_MAX_PAGE_SIZE) {
            DataPage nextDataPage = getWebApiDataPage(firstPageId + 1, dataPageQueryParameters, recordInstanceId);

            fullDataPages.addAll(nextDataPage.getData());
        }

        // In the case of a count, we should have the "real" total Size set by the getWebApiDataPage() method.
        // This is because the Platform, in the Datapage call, will return the number of items in the Datapage,
        // and not the total size of items.
        int totalSize = isRequestForCountOnly ? firstDataPage.getTotalSize() : fullDataPages.size();

        return new DataPage(totalSize, fullDataPages);
    }

    /**
     * This method gets a specific Rawg.io page (if recordInstanceId is null).
     * This method gets a specific Rawg.io game (if recordInstanceId is not null).
     * This is due to the paging limitation of Rawg.io explained in the method "getDataPage()".
     *
     * @param pageId                  int, Rawg.io page Id to fetch.
     * @param dataPageQueryParameters DataPageQueryParameters, datapagequeryparameters object sent by the grid
     *                                containing for example the pagination, search, displayed columns, filters, sorting etc...
     *                                For example sorting by the "name" column or searching for "foo".
     * @param recordInstanceId,       [OPTIONAL (null)] String record instance id to fetch (if we want to fetch
     *                                one specific entry.
     * @return DataPage, DataPage object containing the number of records to get.
     */
    private DataPage getWebApiDataPage(int pageId, DataPageQueryParameters dataPageQueryParameters, String recordInstanceId) {
        // Parameters that will be used to query the Web Api record definition
        Map<String, List<String>> dataPageParams = new HashMap<String, List<String>>();
        // The qualification will contain the different parameters passed to the Rest Api.
        String myQualification = "";

        /**
         * I) Building the datapagequery parameters.
         */
        //  Standard datapagequery.
        dataPageParams.put("dataPageType", new ArrayList<String>(Arrays.asList(DATAPAGEQUERY_TYPE)));

        // List of fields to fetch, here all the fields required by the grid (Custom Record Definition) are
        // also required for the Web Api Record Definition.
        // We just transfer the request.
        List<String> propertySelections = new ArrayList<String>();
        propertySelections.addAll(dataPageQueryParameters.getPropertySelections());

        // We add the "count" field. This "count" field is special
        // as in our case it contains the number of records returned by the Rawg.io
        // rest api, since the one returned by the Platform is always the number
        // of records returned, and not the "real" total number of records available.
        if (rawgioCountFieldId != null && !propertySelections.contains(rawgioCountFieldId)) {
            propertySelections.add(rawgioCountFieldId);
        }

        dataPageParams.put("propertySelection", new ArrayList<String>(propertySelections));

        // We want to fetch data from the Web Api Record Definition.
        dataPageParams.put("recorddefinition", new ArrayList<String>(Arrays.asList(webApiRecordDefinition.getName())));

        // Number of records to return, we just transfer the values passed by the grid.
        // Since the Web Api does not understand those values, we will use them later to build
        // the qualification using the Rawg.io Rest Api query parameters "page" and "page_size".
        // Those two settings are not important, as they will be ignored by the Platform call to the web api.
        dataPageParams.put("pageSize", new ArrayList<String>(Arrays.asList(Integer.toString(dataPageQueryParameters.getPageSize()))));
        dataPageParams.put("startIndex", new ArrayList<String>(Arrays.asList(Integer.toString(dataPageQueryParameters.getStartIndex()))));

        /**
         * II) Building the Web Api qualification.
         * Most of the parameters are not understood by the Web Api / Rawg.io Rest Api.
         * For example, a datapagequery uses pageSize and startIndex for pagination, Rawg.io expects
         * query parameters page and page_size.
         * We have to build a qualification (queryExpression) that would "convert" from a datapagequery parameter to something that
         * Rawg.io understands.
         */
        /** Pagination */
        // As we saw earlier the pagination is passed by the grid through
        // pageSize = dataPageQueryParameters.getPageSize() and startIndex = dataPageQueryParameters.getStartIndex().
        // However the Rawg.io rest api does not understand those parameters pageSize and startIndex as it uses page and page_size.
        // We need the calculate the Rawg.io "page". The first page begins is 1 for Rawg.io.
        // The "page" Web Api query parameter is declared in the Web Api Record Definition as "GET_QUERY_PARAMETER_page" and has a field Id,
        // for example 5310000001.
        // Here we build a qualification to leverage this field, so:
        // '{page field Id}' = "{pageId}"
        // '5310000001' = "1"
        String pageFieldId = getQueryParameterFieldId("page");
        myQualification = "'" + pageFieldId + "' = \"%PAGE_ID%\"";
        myQualification = myQualification.replace("%PAGE_ID%", Integer.toString(pageId));

        // Same principle for the page_size, we continue to build the qualification.
        // Same for the page size which is saved as "GET_QUERY_PARAMETER_page_size" with a fieldId, for example 5310000002.
        // Here we build a qualification to leverage this field, so:
        // AND '{page_size field Id}' = "{page size}"
        // AND '5310000002' = "25"
        String pageSizeFieldId = getQueryParameterFieldId("page_size");
        myQualification += " AND '" + pageSizeFieldId + "' = \"%PAGE_SIZE%\"";
        // Rawg.io seems to have a limitation where the page_size is only 40, which is very unfortunate for us.
        // So we'll have to paginate twice. In this case, we always ask for 25 records.
        myQualification = myQualification.replace("%PAGE_SIZE%", Integer.toString(RAWGIO_MAX_PAGE_SIZE));

        /** Sort */
        // The sort is provided by the sortBy datagequery parameter which is an array of columns.
        // Once again as for the qualification we have to replace the fields labels by ids.
        // We have to check on what to sort on, and its direction (Ascending or Descending).
        // The format expected by the Rawg.io rest api is:
        // &ordering=-name,released
        // Note:
        // As per Rawg.io, it is not possible to sort on some fields, only those are available:
        // https://api.rawg.io/docs/#tag/games
        // name, released, added, created, updated, rating, metacritic
        //
        // The ordering is also a Query Parameter, which is saved as "GET_QUERY_PARAMETER_ordering" with a fieldId, for example 5310000003.
        // Here we build a qualification to leverage this field, so:
        // AND '{ordering field Id}' = "{fields to order on}"
        // AND '5310000003' = "-name,released"
        String sortBy = "";

        for (SortByValue sortOrder : dataPageQueryParameters.getSortByValues()) {
            String fieldId = sortOrder.getPropertyName();
            String webApiFieldName = "";

            // We look for the field Id and we replace by the name, which is stored in the Web Api mapping, at least in our case.
            // For example for the Web Api Record Definitionfield Id "1" the mapping in the Rawg.io Rest Api could be results.name,
            // but in our Definition it is stored as "results||id". We will parse this to extract the Rawg.io property ("id") here:
            // 1 => results||id => id
            // We also have to do this as the grid usually adds a sort on the "id" column, which sadly we cannot sort on.
            RecordDefinitionFieldMapping restExternalMapping = fieldDefinitionByFieldIdList.get(fieldId).getFieldMapping();

            if (restExternalMapping != null) {
                String[] restPath = ((ExternalRecordDefinitionFieldMapping) restExternalMapping).getExternalFieldId().split("\\|");

                webApiFieldName = restPath[restPath.length - 1];
            }

            if (!webApiFieldName.equals("") && isFieldSortable(webApiFieldName)) {
                if (!sortBy.equals("")) {
                    sortBy += ",";
                }

                if (sortOrder.getIsAscending()) {
                    webApiFieldName = "-" + webApiFieldName;
                }

                sortBy += webApiFieldName;
            }
        }

        if (!sortBy.equals("")) {
            String sortFieldId = getQueryParameterFieldId("ordering");
            myQualification += " AND '" + sortFieldId + "' = \"%FIELD_ID%\"";
            myQualification = myQualification.replace("%FIELD_ID%", sortBy);
        }

        /** Search */
        // As other parameters we need to pass to the Web Api, we need to add the search in the qualification.
        // Note:
        // The search is broken in Rawg.io. Some results are not returned, and even using search_exact might return
        // several records...
        //
        // The search is also a Query Parameter, which is saved as "GET_QUERY_PARAMETER_search" with a fieldId, for example 5310000004.
        // Here we build a qualification to leverage this field, so:
        // AND '{search field Id}' = "{value to search}"
        // AND '5310000004' = "foo"
        if (recordInstanceId == null) {
            // Here we are handling the grid "Global Search" and regular search (filters), which add a "queryExpression" in the original
            // datapageQueryParameters. We have something like this, where we are just interested in the value that is searched, "foo":
            // queryExpression = '1' LIKE "%foo%" AND '8' LIKE "%foo%"
            // We need to "translate" into this qualification which is expected by the Rawg.io Rest api:
            // &search=foo
            String initialQueryExpression = dataPageQueryParameters.getQueryPredicatesByName().get("queryExpression").getRightOperand();
            String searchedValue = null;
            String platformList = null;
            HashMap<String, ArrayList<HashMap<String, String>>> searchFieldIdMapping = parseQueryExpression(initialQueryExpression);

            for (String fieldId : searchFieldIdMapping.keySet()) {
                String fieldName = fieldDefinitionByFieldIdList.get(fieldId).getName();

                // If we search in the "name" (here the fieldId is "536870913"), we sadly can only take the first value if there are several,
                // like in:
                // ('536870913' = "test" OR '536870913' = "me" OR '536870913' like "%foobar%")
                // AND ('536870916' = "187" OR '536870916' = "4" OR '536870916' = "5")
                // We can only search for "test" (this is how Rawg.io works).
                if (fieldName.equals("name") && searchedValue == null) {
                    searchedValue = searchFieldIdMapping.get(fieldId).get(0).get("value");

                    // Removing the leading and trailing % if necessary, if the End User looked for %foobar%
                    // it will be changed to foobar.
                    if (searchedValue != null && !searchedValue.isEmpty() && searchFieldIdMapping.get(fieldId).get(0).get("operator").toUpperCase().equals("LIKE")) {
                        searchedValue = searchedValue.replace("%", "");
                    }
                }

                // We need to build a list, but we need to check that the format is correct
                // aka only digits for Rawg.io:
                // &platforms=123,456
                if (fieldName.equals("platforms")) {
                    for (HashMap<String, String> platformFilter : searchFieldIdMapping.get(fieldId)) {
                        String platform = platformFilter.get("value");

                        if (isOnlyDigits(platform)) {
                            if (platformList != null) {
                                platformList += "," + platform;
                            } else {
                                platformList = platform;
                            }
                        }
                    }
                }
            }

            if (searchedValue != null && !searchedValue.isEmpty()) {
                String searchFieldId = getQueryParameterFieldId("search");
                myQualification += " AND '" + searchFieldId + "' = \"%FIELD_ID%\"";
                myQualification = myQualification.replace("%FIELD_ID%", searchedValue);
            }

            if (platformList != null && !platformList.isEmpty()) {
                String platformsFieldId = getQueryParameterFieldId("platforms");
                myQualification += " AND '" + platformsFieldId + "' = \"%FIELD_ID%\"";
                myQualification = myQualification.replace("%FIELD_ID%", platformList);
            }
        } else {
            // We want to get a specific Entry, usually called by the "getRecordInstance()" method.
            // In this case we use the search_exact and the search query parameters which are
            // saved as "GET_QUERY_PARAMETER_search" and "GET_QUERY_PARAMETER_search_exact" with fieldIds,
            // for example 5310000004 and 5310000005.
            // Here we build a qualification to leverage those fields, so:
            // AND '{search field Id}' = "{value to search}" AND '{search_exact field Id}' = "true"
            // AND '5310000004' = "foo" AND '5310000005' = "true"
            // https://api.rawg.io/api/games?search=<slug></>&search_exact=true
            // https://api.rawg.io/api/games?search=grand-theft-auto-v&search_exact=true
            String searchFieldId = getQueryParameterFieldId("search");
            myQualification += " AND '" + searchFieldId + "' = \"%FIELD_ID%\"";
            myQualification = myQualification.replace("%FIELD_ID%", recordInstanceId);

            String searchExactFieldId = getQueryParameterFieldId("search_exact");
            myQualification += " AND '" + searchExactFieldId + "' = \"%FIELD_ID%\"";
            myQualification = myQualification.replace("%FIELD_ID%", "true");
        }

        /**
         * III) Building the datapage query Parameters and calling the Web Api Record definition.
         * datagequery.
         */
        // Adding the final queryExpression.
        dataPageParams.put("queryExpression", new ArrayList<String>(Arrays.asList(myQualification)));
        DataPageQueryParameters queryParameters = new DataPageQueryParameters(dataPageParams);

        // Fetching records from the Web Api Record Definition.
        DataPage webApiDataPage = recordService.getRecordInstancesByIdDataPage(queryParameters);
        int restApiCallTotalSize = 0;

        // We try to return the "real number of games.
        // The Platform returns as total size the number of items, when we are interested by the
        // total number of games available.
        if (!webApiDataPage.getData().isEmpty()) {
            if (rawgioCountFieldId != null) {
                restApiCallTotalSize = ((HashMap<String, Integer>) (webApiDataPage.getData().get(0))).get(rawgioCountFieldId);
            }
        }

        return new DataPage(restApiCallTotalSize, webApiDataPage.getData());
    }


    /**
     * It seems this method is supposed to return a record instance, for example when called from a
     * Record editor, however it does not seem to work (?).
     * <p>
     * IMPORTANT:
     * This method does not seem to be called when getting a record instance:
     * http://server:post/api/rx/application/record/recordinstance/{Record Definition Name}/{Guid}
     * It is unclear how it is used, so, it is a WIP as it cannot be tested...
     * The method called is actually "getDataPage()" for some reason.
     * Moreover, this is rather useless as a Custom Record cannot be used in a Record Editor...
     * Maybe it could be used in a "Get Record" process activity (?).
     * <p>
     * Technically you cannot have a custom record definition in a record editor and there is no way to select one entry
     * in an External Record Definition type web api, which is designed to return a list of items.
     * So we could just return nothing for now, unless we really want to be "compliant" / user friendly as much as possible.
     * In this case we would need to specifically search the External Record definition for one item, fetch it, and return it.
     * However, the problem is that the Rest Api is different to get a Games details:
     * https://api.rawg.io/api/games/{id}
     * https://api.rawg.io/api/games/3498
     * So we cannot use the Web api we defined to get the list of games.
     * <p>
     * As a measure of good faith, we could implement another Web Api object, but then the issue would be how to pass the Id to it
     * or know what is the Web Api Record Definition to call.
     * What we can do however, is to perform a datapagequery call with a "precise search" on the "slug" field, if we have it, which
     * might not be the case, unless the "ID" field mapped in the External record definition was the "slug" field.
     * https://api.rawg.io/api/games?search=<slug></>&search_exact=true
     * https://api.rawg.io/api/games?search=grand-theft-auto-v&search_exact=true
     * <p>
     * IMPORTANT:
     * It seems the search functionality is busted in the Rawg.io /games rest api, as many exact search return several values, or
     * just cannot be found.
     *
     * @param recordDefinitionName, String, custom record definition name.
     * @param recordInstanceId,     String, record instance Id, in our example it is the "slug".
     * @return RecordInstance, object as a RecordInstance.
     */
    @Override
    public RecordInstance getRecordInstance(String recordDefinitionName, String recordInstanceId) {
        RecordInstance recordInstance = new RecordInstance();
        // We need to create some datapage parameters with the strict minimum information.
        // We only want one record.
        DataPageQueryParameters dataPageQueryParameters = null;
        Map<String, List<String>> dataPageParams = new HashMap<String, List<String>>();
        String myQualification = "";

        // Standard datapage type.
        dataPageParams.put("dataPageType", new ArrayList<String>(Arrays.asList(DATAPAGEQUERY_TYPE)));

        // We want to get all fields back.
        // LMA:: TODO:: Add all fields (?)
        List<String> propertySelections = new ArrayList<String>();
        dataPageParams.put("propertySelection", new ArrayList<String>(propertySelections));

        // We want to fetch data from the Web Api Record Definition.
        dataPageParams.put("recorddefinition", new ArrayList<String>(Arrays.asList(webApiRecordDefinition.getName())));

        // Number of records to return (1) and first page.
        // It is not important, as they will be ignored by the Platform call to the web api.
        dataPageParams.put("pageSize", new ArrayList<String>(Arrays.asList("1")));
        dataPageParams.put("startIndex", new ArrayList<String>(Arrays.asList("0")));

        /** Pagination */
        // Please check the method "getWebApiDataPage" for details.
        // We only want one record back, so page is 1 and page_size is 1.
        String pageFieldId = getQueryParameterFieldId("page");
        myQualification = "'" + pageFieldId + "' = \"%PAGE_ID%\"";
        myQualification = myQualification.replace("%PAGE_ID%", "1");

        String pageSizeFieldId = getQueryParameterFieldId("page_size");
        myQualification += " AND '" + pageSizeFieldId + "' = \"%PAGE_SIZE%\"";
        myQualification = myQualification.replace("%PAGE_SIZE%", "1");

        /** Adding the final queryExpression: */
        dataPageParams.put("queryExpression", new ArrayList<String>(Arrays.asList(myQualification)));
        dataPageQueryParameters = new DataPageQueryParameters(dataPageParams);

        // Getting the dataPage:
        DataPage recordInstanceDataPage = getWebApiDataPage(1, dataPageQueryParameters, recordInstanceId);

        // LMA:: TODO:: Conversion.
        // We need to "convert" the DataPage to make it a RecordInstance.
        int totalSize = recordInstanceDataPage.getTotalSize();

        return recordInstance;
    }

    /**
     * Returns the name of the Custom Record Definition Data Source Provider.
     * It will be displayed in Administration.
     *
     * @return String, the Data Source Provider.
     */
    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    /**
     * It seems this method is supposed to return the count of a datapagequery.
     * By default the count is not returned, usually for performances reason.
     * <p>
     * IMPORTANT:
     * It seems this method is not called when we click in the grid on the "50+" link to get the count.
     * Maybe it is used by getRecordInstanceCount_ process activity (?).
     *
     * @param recordDefinitionName,    String, custom record definition name.
     * @param dataPageQueryParameters, DataPageQueryParameters, datapagequeryparameters object sent by the grid
     *                                 containing for example the search, displayed, columns, filters, sorting etc...
     * @param customRecordDefinition,  Set<DataProviderMappingConfig>, custom record definition.
     * @param noIdea,                  Set<Integer>, it seems it's an array of fields, maybe unique Ids (?) [1, 379, 380].
     * @return Integer, the count.
     */
    @Override
    public Integer getRecordInstanceCount(String recordDefinitionName, DataPageQueryParameters dataPageQueryParameters, Set<DataProviderMappingConfig> customRecordDefinition, Set<Integer> noIdea) {
        // In this case get the rest api call, and just use the count.
        // If this is a real count we should have:
        //  pageSize is 1, startIndex is 0 and shouldIncludeTotalSize is "true"
        DataPage dataPage = getDataPage(recordDefinitionName, dataPageQueryParameters, customRecordDefinition, noIdea);

        return dataPage.getTotalSize();
    }

    /**
     * The goal is to map the different filters and searches by fieldId, so if we have a combination
     * of filters (test, me, %foobar% for name, and Xbox, PS1 for platforms) and grid global search (foo) such as:
     * ('536870913' = "test" OR '536870913' = "me" OR '536870913' like "%foobar%")
     * AND ('536870916' = "xbox" OR '536870916' = "PS1")
     * AND ('536870913' LIKE "%foo%" OR '536870914' LIKE "%foo%" OR '536870916' LIKE "%foo%")
     * We would like to end up having a mapping of different operators and values, per field id:
     * fieldIdMapping["536870913"] = [
     * {
     * "operator": "=",
     * "value": "test"
     * },
     * {
     * "operator": "=",
     * "value": "me"
     * },
     * {
     * "operator": "like",
     * "value": "%foobar%"
     * }
     * ]
     * <p>
     * Note:
     * This code has been generated by ChatGPT and modified.
     * Who said I don't credit AI work :)
     * Don't hit me Skynet...
     *
     * @param queryExpression String
     * @return HashMap<String, ArrayList < HashMap < String, String>>> mapping of fieldIds with their matching operators and values.
     */
    private HashMap<String, ArrayList<HashMap<String, String>>> parseQueryExpression(String queryExpression) {
        HashMap<String, ArrayList<HashMap<String, String>>> fieldIdMapping = new HashMap<>();

        // Regular expression pattern to match fieldId, operator, and value.
        Pattern pattern = Pattern.compile("'(\\d+)' (?:=|like|LIKE) \"([^\"]+)\"");
        Matcher matcher = pattern.matcher(queryExpression);

        // Iterate through the matches and populate the mapping
        while (matcher.find()) {
            String fieldId = matcher.group(1);
            String operator = matcher.group(0).toUpperCase().contains("' LIKE \"") ? "LIKE" : "=";
            String value = matcher.group(2);

            HashMap<String, String> condition = new HashMap<>();
            condition.put("operator", operator);
            condition.put("value", value);

            if (!fieldIdMapping.containsKey(fieldId)) {
                fieldIdMapping.put(fieldId, new ArrayList<>());
            }

            fieldIdMapping.get(fieldId).add(condition);
        }

        return fieldIdMapping;
    }

    /**
     * A Platform Id in Rawg.io is an integer.
     * Since we allow in the UI to filter by an Advanced Filter, an End User can
     * select a Platform through a Named List, which will return a string with the
     * right value, but it can also be hardcoded as a string, which would be a problem.
     * This method checks that the input string has only digits.
     *
     * @param platformId String, Rawg.io platform id.
     * @return boolean, is the input string has only digits.
     */
    private boolean isOnlyDigits(String platformId) {
        if (platformId == null) {
            return false;
        }

        return platformId.matches("\\d+");
    }
}
