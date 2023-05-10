# openlineage-openmetadata-transporter
This is an extension for the [OpenLineage](https://github.com/OpenLineage/OpenLineage) project, providing a custom transporter for integrating OpenLineage with OpenMetadata.
To use 'openMetadata' transport type, make sure that you have openlineage-spark jar and OpenLineage_OpenMetadata_Transporter jar in your classpath.


Lineage collected by OpenLineage will be transmitted to OpenMetadata through APIs, including:
1. Create/update pipeline service.
2. Create/update pipeline.
3. Create/update lineage edge, connecting the inlet/outlet tables collected by OpenLineage to the pipeline.
4. Update custom property "lastUpdateTime" for output tables.

Note: lineage will be reported only for tables that already exist in OpenMetadata metadata store.

Using the 'openMetadata' transport type requires adding the following spark configuration parameters:

| Parameter                                    | Definition                                                                                    | Example                                    |
----------------------------------------------|-----------------------------------------------------------------------------------------------|--------------------------------------------
| spark.openlineage.transport.type             | The transport type used for event emit. Set to `openMetadata` to use OpenMetadata transport.  | openMetadata                               |
| spark.openlineage.transport.auth.type      | Authentication type when sending events to the OpenMetadata server                            | api_key                                    |
| spark.openlineage.transport.auth.apiKey      | An API key to be used when sending events to the OpenMetadata server                          | abcdefghijk                                |
| spark.openlineage.transport.timeout          | Timeout for sending OpenLineage info in seconds. Default is 5 seconds.                        | 30                                         |
| spark.openlineage.transport.url              | The url of the OpenMetadata API server where events should be reported                        | http://my-openMetadata-staging             |
| spark.openlineage.transport.pipelineServiceName              | Airflow pipeline service name, as defined in OpenMetadata. Will be created if it doesn't exist. | my-airflow-staging |
| spark.openlineage.transport.pipelineUrl              | The pipeline's url in Airflow, as defined in OpenMetadata. Will be created if it doesn't exist. | http://my-airflow-staging/tree?dag_id=my-etl |
| spark.openlineage.transport.pipelineName              | The pipeline's name, as defined in OpenMetadata. Will be created if it doesn't exist.         | my-etl                                     |
| spark.openlineage.transport.airflowHost              | Airflow uri.                                                                                  | http://my-airflow-staging                  |
| spark.openlineage.transport.pipelineDescription      | Optional pipeline description. | This is my ETL                          |

