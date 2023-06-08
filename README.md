# openlineage-openmetadata-transporter
This is an extension for the [OpenLineage](https://github.com/OpenLineage/OpenLineage) project, providing a custom transporter for integrating OpenLineage with OpenMetadata.

Lineage collected by OpenLineage will be transmitted to OpenMetadata through APIs, including:
1. Create/update pipeline service (Airflow is assumed).
2. Create/update pipeline.
3. Create/update lineage edge, connecting the inlet/outlet tables collected by OpenLineage to the pipeline in transport configuration. It is assumed that the table already exists in OpenMetadata, otherwise it will not be created.
4. Update custom property "lastUpdateTime" for output tables.

To use 'openMetadata' transport type with your application, you will need openlineage-spark jar and openlineage-openmetadata-transporter jar in your classpath.
Using the 'openMetadata' transport type requires adding the following spark configuration parameters:

| Parameter                                       | Definition                                                                                   | Example                     |
-------------------------------------------------|----------------------------------------------------------------------------------------------|-----------------------------
| spark.openlineage.transport.type                | The transport type used for event emit. Set to `openMetadata` to use OpenMetadata transport. | openMetadata                |
| spark.openlineage.transport.auth.type           | Authentication type when sending events to the OpenMetadata server                           | api_key                     |
| spark.openlineage.transport.auth.apiKey         | An API key to be used when sending events to the OpenMetadata server                         | abcdefghijk                 |
| spark.openlineage.transport.url                 | The url of the OpenMetadata API server where events should be reported                       | http://my-openMetadata-host |
| spark.openlineage.transport.pipelineName        | The pipeline's name. Will be created in OpenMetadata if it doesn't exist.                    | my-pipeline                 |
| spark.openlineage.transport.pipelineServiceUrl  | The pipeline service (Airflow) url.                                                          | http://my-airflow-host      |
| spark.openlineage.transport.pipelineDescription | Optional pipeline description.                                                               | This is my ETL              |
| spark.openlineage.transport.timeout             | Optional timeout for sending OpenLineage info in seconds. Default is 5 seconds.              | 30                          |


## Extracting lineage from JDBC queries using java instrumentation

Extracting lineage from JDBC queries running directly on JDBC connections, outside of spark context.
The project provides a java agent that intercepts the JDBC queries as they are executed, identifies the input and output tables and sends the lineage information to OpenMetadata, 
using the 'openMetadata' transport type.


To use the java agent, include the openlineage-openmetadata-transporter jar in your class path and specify the jar file as a java agent, with the relevant transport configuration added as follows:

```
-javaagent:/path/to/openlineage-openmetadata-transporter.jar=transport.pipelineServiceUrl=http://my-airflow-host,transport.auth.apiKey=myJwtToken,transport.pipelineName=my-pipeline,transport.url=http://my-openMetadata-host'
```
