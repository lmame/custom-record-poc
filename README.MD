# Custom Record Library (com.example.customrecordlibrary)
History:
* Version:
    * 0.1, 2021/08/15, Initial WIP.
* Author: Laurent Matheo.


## Table Of Contents:
* [What is this](#what-is-this),
* [Installation (using the zip file)](#installation),
* [Updating your SDK / Developer Environment (if needed)](#update),
* [Build and installation using the source code](#build),
* [Challenges](#challenges),
* [Configuration](#configuration),


<a name="what-is-this"></a>
## What is this? :)
The goal of this example is double:
* Show how to create a coded "Custom record definition",
* Try to do a better implementation of the External Record Definition type Web Api using the Coded Custom Record Definition as an interface,


<a name="installation"></a>
## Installation (using the zip file):
* You can just deploy the file "com.example.customrecordlibrary-1.0-SNAPSHOT.zip" on your development environment. The system must be at least 22.1.03.  
  * Here are the [instructions](https://docs.bmc.com/docs/is221/deploying-your-digital-service-application-for-the-first-time-to-start-working-in-bmc-helix-innovation-studio-1039589996.html?src=search) to start working with Innovation Studio.  
* A codeless test bundle that consumes this custom record is available in the "_codeless bundle/" sub folder. Just deploy "com.example.games-1.0.0-INSTALL.zip".
* Check the [Configuration](#configuration) section to:
  * Create the:
    * Web api connection,
    * Data source connections,
  * Perform the mappings:
    * Web Api mapping,
    * Data source mappings,

> :memo:  
> This bundle has been created using the 22.1.03 SDK and only contains Java code, so should work as well on 23.3.00 environments.


<a name="update"></a>
## Updating your SDK / Developer Environment (if needed):
* [22.x SDK upgrade](https://docs.bmc.com/docs/is221/upgrading-to-the-latest-bmc-helix-innovation-studio-sdk-1039588344.html?src=search),


> :memo:  
> This bundle has been created using the 22.1.03 SDK and only contains Java code, so should work as well on 23.3.00 environments.


<a name="build"></a>
## Build and installation using the source code:
Please follow those steps:
* Prepare your developer environment installing several [pre-requisites](https://docs.bmc.com/docs/is221/setting-up-your-ide-and-installing-bmc-helix-innovation-studio-sdk-1039589980.html?src=search),
  * You will need nodeJs, maven, Yarn, Grunt and Java (OpenJDK).

Here are the steps:
* Clone this GitHub repository,
* Change the content of the parent "pom.xml" file to insert your credentials:
```xml
    <!-- START: Bundle specific configuration. Verify and Change as per environment -->
    <developerUserName>developer</developerUserName>
    <developerPassword>password</developerPassword>
    <!-- Server name with Jetty port. -->
    <webUrl>https://innovationstudio-server:innovationstudio-port</webUrl>
    <!-- END: Bundle specific configuration.-->
```
* run this command to build to deploy the coded application on your developer environment as per the [documentation](https://docs.bmc.com/docs/is221/deploying-your-digital-service-application-for-the-first-time-to-start-working-in-bmc-helix-innovation-studio-1039589996.html?src=search):
  * ```bash
    mvn clean install -Pdeploy
    ```


<a name="challenges"></a>
## Challenges:  
What is the purpose of this example?  
Creating an External Record Definition type "Web Api" allows us to easily query a GET Rest Api, for example getting a list of video games and display this information in a grid.  
However, it comes with several limitations as stated here in this video example:
https://www.youtube.com/watch?v=iPGOkeKMNjY
It is not possible to:
* Search,
* Filter,
* Sort,
* Paginate (backend will always return the first 50 records),
The reason is each Rest Api vendor implement (or not) those features with different parameters.

<a name="idea"></a>
## Idea:
The idea of this example is to use a Custom Record object to act as an intermediate.  
Some specs are available here in [Confluence](https://confluence.bmc.com/display/SolRD/%5BNon+Official%5D+Improve+Web+Api+External+Record+Definition) (WIP).    
Instead of doing:
```
View with a grid -> External record (web api) ----[Rest Api]----> Vendor
```
We would have:
```
View with a grid -> Custom Record -> External record (web api) ----[Rest Api]----> Vendor
```

Why do this, adding this additional step?  
The custom record will:
* Receive the search, filter, sort, pagination instructions from the OOTB grid (datapagequery),
* "translate" those datapagquery parameters to those the Rest Api understands,
* Forward the new datapagequery to the External Record,

<a name="example"></a>
## Example:
Let's say that we have a grid and we scroll down to get the "second" page. The datapageequery would be this one (it is a POST call in the latest releases):
```
http://<server>:<port>/api/rx/application/datapage
```
```json
{
    "values": {
        "dataPageType": "com.bmc.arsys.rx.application.record.datapage.RecordInstanceDataPageQuery",
        "pageSize": "50",
        "startIndex": "50",
        "recorddefinition": "com.example.pizza:Orders",
        "shouldIncludeTotalSize": "false",
        "propertySelection": "1,6,8,379"
    }
}
```
We have two parameters, startIndex and pageSize. If we want to get the page "1" (first page), we would have:
```
"pageSize": "0",
"startIndex": "50",
```
If we want page "2":
```
"pageSize": "50",
"startIndex": "50",
```
A vendor Rest API might have something like this however:  
```
http://<vendorRestApi>/?page=<pageId>&page_size=<pageSize>
```
  
So in order to get the second pae we would have to "translate" the OOTB datapagequery options from:
```
"pageSize": "50",
"startIndex": "50",
```
To something like this:
```http://<vendorRestApi>/?page=2&page_size=50```
To do this, we will have to build a qualification and pass it to the External Record type Web API (the flow is the next chapter):
```'page'=2 AND 'page_size'=50```


<a name="implementation"></a>
## Implementation details (Flow):
In this example, we use the free rest api provider Rawg.io to get a list of games.  
Here is the Api documentation:
* https://api.rawg.io/docs/#operation/developers_read
* https://api.rawg.io/api/games

> :memo:
> We need an api key that needs to be provided with all api calls to Rawg.io:
> ```https://api.rawg.io/api/games?key=<apiKey>```
> You can use the api key "a6e082bd0974424b859762036fed6384" for the time being,

> :memo:
> The fieldId given in this example might be different in your implementation if you are following this example.  
  
  
When calling the External record definition type Web Api we have to build the parameters using the ```DataPageQueryParameters``` ```queryExpression```.  
Indeed, in order to set values to a Rest Api path / query parameter the Platform automatically creates a "fake" field, for example "GET_QUERY_PARAMETER_<query-parameter>".  

For example here the "page" query parameter needs to set to fetch a specific page for the Rest api:
```https://api.rawg.io/api/games?key=<apiKey>&page=2```
When creating the Web Api object, we need to define the "page" as a query parameter (and other necessary query parameters, but we will see that later).  
When creating the external record definition type Web api, the server will automatically a "fake" field "GET_QUERY_PARAMETER_page" with a field Id (let's say "30002001").  
Now, to set the "page" to 2, we would need to set the field "GET_QUERY_PARAMETER_page" to 2 in the qualification, for example:
```'30002001'=2```
The Platform will then translate this queryExpression to the right Rest api query parameter:
```https://api.rawg.io/api/games?page=2```
  
In this example, we are going to support multiple query parameters, so we need to add them to the Web Api query parameters:    
```page, page_size, search, search_exact, platforms, genres, ordering.```
Once creating the External Record type Web Api, the Platform will create fields accordingly:
```GET_QUERY_PARAMETER_page, GET_QUERY_PARAMETER_page_size, GET_QUERY_PARAMETER_ordering``` with their fieldIds.  
For example if the field "GET_QUERY_PARAMETER_ordering" could have the fieldId "30002007".  


### Pagination:  
For pagination, we would need to set the rest api ```page``` and ```page_size``` rest api query parameters, two fields are created, GET_QUERY_PARAMETER_page (30002001) and  GET_QUERY_PARAMETER_page_size (30002002).  
If we want to set the page to 2 and the page_size to 50, we want to ultimately achieve:
```&page=2&page_size=50```
So we need to build this qualification for the external record definition type web api:  
```AND '<fieldId-page>'=2 AND '<fieldId-page_size>'=50```
So:
```AND '30002001'=2 AND '30002002'=50```

### Global search:
To support the grid "global" search, we need to set the rest api ```search``` query parameter:

> :memo:  
> There are different search options in /games/ Rawg.io rest api, we are not going to implement all of them in this example. In our example:
>  ```search_exact (boolean)```, which marks the search query as exact, should be set to false.
  
For example searching for the string "foo":
```&search=foo&search_exact=false```  
So to build this qualification for the external record definition type web api datapagequery we would have:  
```AND '<fieldId-search>'="foo" AND '<fieldId-search_exact>'=false```
So:
```AND '30002003'="foo" AND '30002004'=false```
  
### Filtering:
To support filtering from the grid, there are multiple filters available (platforms, genres, etc...), the expected format is <value1>,<value2>, for example:
```&platforms=(xbox,3DS)&genres=(4,51 or action,indie)```
 
So to build this qualification for the external record definition type web api datapagequery:
```AND '<fieldId-platforms>'="xbox,3DS" AND '<fieldId-genres>'="action,indie"```
So:
```AND '30002005'="xbox,3DS" AND '30002006'="action,indie"```

> :memo:
> There are many different filters in /games/ Rawg.io rest api, we are not going to implement all of them in this example.

### Sorting:  
To support Sorting, we need to handle the rest api "ordering" query parameter.  
In Rawg.io /games/ rest api, the available fields for sorting are:
```name, released, added, created, updated, rating, metacritic```  
You can reverse the sort order adding a hyphen, for example: ```-released```.
For example, if we want to sort on the "name" (ascending) and "released" (descending), we would like to achieve:  
```&ordering=name,-released```
So to build this qualification for the external record definition type web api datapagequery:  
```AND '<fieldId-ordering>'="name,-released"```
So:  
```AND '30002007'="name,-released"```  

### How to "merge" all options together?:
Overall we could have many expressions in the final datapagequery queryExpression, in our example:
* Pagination:
  * ```'<fieldId-page>'=2 AND '<fieldId-page_size>'=50```
* Search:
  * ```AND '<fieldId-search>'="foo" AND '<fieldId-search_exact>'=false```
* Filtering:
  * ```AND '<fieldId-platforms>'="xbox,3DS" AND '<fieldId-genres>'="action,indie"```
* Sorting:
  * ```AND '<fieldId-ordering>'="name,-released"```
  
The final query expression would then be:
  * ```'<fieldId-page>'=2 AND '<fieldId-page_size>'=50 AND '<fieldId-search>'="foo" AND '<fieldId-search_exact>'=false AND '<fieldId-platforms>'="xbox,3DS" AND '<fieldId-genres>'="action,indie" AND '<fieldId-ordering>'="name,-released"```
  * So:
    * ```'30002001'=1 AND '30002002'=50 AND '30002003'="foo" AND '30002004'=false AND '30002005'="xbox,3DS" AND '30002006'="action,indie" AND '30002007'="name,-released"```


<a name="implementationJava"></a>
## Implementation details (Java):
See [source code](./bundle/src/main/java/com/example/bundle/VideoGameGamesCustomRecordProvider.java) or the Confluence page for details.  


<a name="configuration"></a>
## Configuration:
### Web Api Connection:
Create a Web Api connection in Administration:
* domain: api.rawg.io,
* protocol: https,
* port: 443,
* authentication: custom
  * (It does not really matter),
* In the next screen, we can set the ```key``` query parameter with the Rawg.io api key, so the "key" will be sent with every rest api calls,
  * Add query parameter
    * On the left field: key
    * On the right field: a6e082bd0974424b859762036fed6384


### Data source (Web Api):
Create a Data source type "web api" in Administration using the web api connection created earlier.


### Data source (Custom):
Create a Data source type "Custom" using our coded provider "rawg.io (Games)" in Administration.


### Web Api Mapping (if you use the demo bundles):
Go in Administration in Web Api Mapping and for the bundle "Games" match for the "rawg.io" connection target the Web Api Connection you created.

### Data Sources Mapping (if you use the demo bundles):
Go in Administration in Data Source Mapping and for the bundle "Games" match:
* For the "Rawg.io datasource" connection target the Web Api data source you created,
* For the "Rawg.io Custom" connection target the Custom data source you created,



### Codeless bundle:
LMA:: TODO:: Screenshots.
Create a codeless bundle,

### Web Api object:
LMA:: TODO:: Screenshots.
In the codeless bundle you just created:
Create a Document that documents the /games/ Rawg.io rest api response, even with the array content since we'll need it for the External record definition,
* You can do this just running the /games/ rest api using your Rawg.io api key in Postman for example, and copy the result, for example (the output has been truncated here): 
```json
{
    "count": 6,
    "next": "https://api.rawg.io/api/games?key=<Rawg.ioRestApiKey>&page=2&page_size=1&platforms=187%2C186&search=Theft",
    "previous": null,
    "results": [
        {
            "slug": "grand-theft-auto-v",
            "name": "Grand Theft Auto V",
            "playtime": 74,
            "platforms": [
                {
                    "platform": {
                        "id": 4,
                        "name": "PC",
                        "slug": "pc"
                    }
                }
            ],
            "stores": [
                {
                    "store": {
                        "id": 1,
                        "name": "Steam",
                        "slug": "steam"
                    }
                }
            ],
            "released": "2013-09-17",
            "tba": false,
            "background_image": "https://media.rawg.io/media/games/456/456dea5e1c7e3cd07060c14e96612001.jpg",
            "rating": 4.47,
            "rating_top": 5,
            "ratings": [
                {
                    "id": 5,
                    "title": "exceptional",
                    "count": 3862,
                    "percent": 58.92
                }
            ],
            "ratings_count": 6460,
            "reviews_text_count": 55,
            "added": 19843,
            "added_by_status": {
                "yet": 510,
                "owned": 11441,
                "beaten": 5557,
                "toplay": 590,
                "dropped": 1041,
                "playing": 704
            },
            "metacritic": 92,
            "suggestions_count": 422,
            "updated": "2023-08-08T12:51:34",
            "id": 3498,
            "score": "18.376041",
            "clip": null,
            "tags": [
                {
                    "id": 31,
                    "name": "Singleplayer",
                    "slug": "singleplayer",
                    "language": "eng",
                    "games_count": 207583,
                    "image_background": "https://media.rawg.io/media/games/942/9424d6bb763dc38d9378b488603c87fa.jpg"
                }
            ],
            "esrb_rating": {
                "id": 4,
                "name": "Mature",
                "slug": "mature",
                "name_en": "Mature",
                "name_ru": "С 17 лет"
            },
            "user_game": null,
            "reviews_count": 6555,
            "saturated_color": "0f0f0f",
            "dominant_color": "0f0f0f",
            "short_screenshots": [],
            "parent_platforms": [
                {
                    "platform": {
                        "id": 1,
                        "name": "PC",
                        "slug": "pc"
                    }
                }
            ],
            "genres": [
                {
                    "id": 3,
                    "name": "Adventure",
                    "slug": "adventure"
                }
            ]
        }
    ],
    "user_platforms": false
}
```
Create a Web Api object with the different query parameters (search, ordering etc...) for the GET "/api/games" rest api,
* The Output payload of this Web Api will use the Document you just created,


### External Record Definition (type Web Api):  
LMA:: TODO:: Screenshots.
Create an External record definition:
* Type Web Api,
* Using the Document created earlier,
* Using the web api created earlier,
* Selecting the relevant fields you want to display in the grid, for example "slug", "name" etc...,
  * The Display Id can be mapped to the "name", and the GUID to the "id" here,
    * The goal is to identify two fields in the Rest Api response that could match what those two Record Definition fields are usually used for:
      * Display Id: An ID that is "user friendly", unique, and identifies the record, most of the time as a sequence (like INC0001, INC0002 etc...),
        * For this example it is recommended to use "id",
      * GUID (ID): A unique identifier of the record instance, usually a Guid,
        * For this example it is recommended to use "slug",
          * This is important to choose "slug" as it will be used to search for one specific entry in the Java method ```public RecordInstance getRecordInstance()```,

> :memo:
> The query parameter fields (page, ordering etc...) will be created automatically after the external record definition is saved.


### Join form:  
LMA:: TODO:: Screenshots.  
> :memo:
> As we cannot use an "External record definition" for a custom record definition, we need to create a join between the same external record definition as a workaround.
  
Create a Join Record Definition:
* Both the Primary and Secondary Record Definition point to the External Record Definition created earlier,
* The Join type is "Inner join",
* No qualification is needed (On statement) as we'll only use it to get the "definition" (fieldIds),
* Add all the fields from the "Primary",


### Connection mapping (Data Source and Web Api):
LMA::TODO:: Create the view for the mapping?  
LMA::TODO:: Screenshots.  
Create a view using in a grid the external record definition.  
Go to Administration to do the different connection mappings for the codeless bundle, map both connections with the ones created earlier:
* "Web API mapping",
* "Data source mapping",

> :memo:  
> Select the codeless bundle first in the dropdown, then the connection previously created.

