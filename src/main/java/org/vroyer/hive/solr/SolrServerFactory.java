package org.vroyer.hive.solr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.solr.client.solrj.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SolrServerFactory {
    private final static Logger LOG = LoggerFactory.getLogger(SolrServerFactory.class);

    private Map<String, CloudSolrClient> urlToCloudSolrServer = new ConcurrentHashMap<String, CloudSolrClient>();
    private static SolrServerFactory instance = new SolrServerFactory();

    public static SolrServerFactory getInstance() {
        return instance;
    }

    private SolrServerFactory() {
    }

    public synchronized CloudSolrClient  createCloudServer(String zkHost, String collectionId) {
        if (!urlToCloudSolrServer.containsKey(collectionId)) {
            HttpClientUtil.setConfigurer(new Krb5HttpClientConfigurer());
            CloudSolrClient server = new CloudSolrClient (zkHost);
            server.setDefaultCollection(collectionId);
            urlToCloudSolrServer.put(collectionId, server);
            LOG.info("Cloud solr server client was created with zkHost={}, collectionId={}", zkHost, collectionId);
            server.connect();
        }
        return urlToCloudSolrServer.get(collectionId);
    }
}