/**
The MIT License (MIT)

Copyright (c) 2014 Vincent ROYER

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 **/
 package org.vroyer.hive.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.TableScanDesc;
import org.apache.hadoop.mapred.JobConf;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;

public class SolrTable {
	private SolrClient server;
	
	protected final int solrSplitSize;
	protected final boolean solrOverwriteMode;
	protected final FluentIterable<String> solrRequiredFilterFields;
	protected final String[] fields;
	protected final String facetType;
	protected final String zkUrl;
	protected final String collectionId;
	protected final String qs;
	protected final StringBuilder fq = new StringBuilder();
	protected final StringBuilder q = new StringBuilder();
	
	private final Collection<SolrInputDocument> outputBuffer;
	
	private static final Logger log = Logger.getLogger(SolrTable.class);

	
	public SolrTable(JobConf conf) {

		KerberosInitializer.init(conf);

		String filterExprSerialized = conf.get(TableScanDesc.FILTER_EXPR_CONF_STR);
		if (filterExprSerialized != null) {
			ExprNodeDesc filterExpr = Utilities.deserializeExpression(filterExprSerialized);
			log.debug("filterExpr="+filterExpr.getExprString());
			SolrStorageHandler.buildQuery(filterExpr,fq,q);
		}
		
        this.zkUrl = ConfigurationUtil.getZkUrl(conf);
		this.collectionId = ConfigurationUtil.getCollectionId(conf);
		this.qs = ConfigurationUtil.getQs(conf);
		this.fields = ConfigurationUtil.getAllColumns(conf.get(ConfigurationUtil.SOLR_COLUMN_MAPPING));
        this.facetType = conf.get(ConfigurationUtil.SOLR_FACET_MAPPING);
        log.info("zk.url="+zkUrl+" solr.collection="+collectionId+" solr.qs="+qs+" fq="+fq+" q="+q);
        
        this.solrSplitSize = ConfigurationUtil.getSolrSplitSize(conf);
        this.solrOverwriteMode = ConfigurationUtil.isSolrOverwriteMode(conf);
		this.solrRequiredFilterFields = FluentIterable.from(ConfigurationUtil.getRequiredFilterFields(conf));
        this.outputBuffer = new ArrayList<SolrInputDocument>(solrSplitSize);
        this.server = SolrServerFactory.getInstance().createCloudServer(zkUrl, collectionId);
	}

	public void save(SolrInputDocument doc) throws IOException {
		outputBuffer.add(doc);
		if (outputBuffer.size() >= solrSplitSize) {
			flush();
		}
	}
	
	public void flush() throws IOException {
		try {
			if (!outputBuffer.isEmpty()) {
				if (solrOverwriteMode) {
					putDoc(outputBuffer);
				} else {
					putDocWithoutCheck(outputBuffer);
				}
				outputBuffer.clear();
			}
		} catch (SolrServerException e) {
			throw new IOException(e);
		}
	}

	public long count() throws IOException {
		return getCursor( 0, 0).getNumFound();
	}

	public SolrTableCursor getCursor(int start, int count) throws IOException {
		return new SolrTableCursor(this, start, (facetType==null) ? count : 1, solrSplitSize);
	}
	
	public void drop() throws IOException{
		try {
			server.deleteByQuery("*:*");
			server.commit();
		} catch (SolrServerException e) {
			throw new IOException(e);
		}
	}

	public void commit() throws IOException {
		try {
			flush();
			server.commit();
		} catch (SolrServerException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public void rollback() throws IOException {
		try {
			outputBuffer.clear();
			server.rollback();
		} catch (SolrServerException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	private void putDoc(Collection<SolrInputDocument> docs) throws IOException, SolrServerException {
		UpdateResponse rsp = server.add(docs);
	}

	private void putDocWithoutCheck(Collection<SolrInputDocument> docs) throws IOException, SolrServerException {
		UpdateRequest request = new UpdateRequest();
		for (SolrInputDocument doc: docs) {
			request.add(doc, false);
		}
		server.request(request);
	}
}
