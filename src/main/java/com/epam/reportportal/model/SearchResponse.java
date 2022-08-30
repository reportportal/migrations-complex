package com.epam.reportportal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public class SearchResponse implements Serializable {
	@JsonProperty
	private Hits hits;

	@JsonProperty
	private int took;

	@JsonProperty("timed_out")
	private boolean timedOut;

	public SearchResponse() {
	}

	public SearchResponse(Hits hits, int took, boolean timedOut) {
		this.hits = hits;
		this.took = took;
		this.timedOut = timedOut;
	}

	public Hits getHits() {
		return hits;
	}

	public void setHits(Hits hits) {
		this.hits = hits;
	}

	public int getTook() {
		return took;
	}

	public void setTook(int took) {
		this.took = took;
	}

	public boolean isTimedOut() {
		return timedOut;
	}

	public void setTimedOut(boolean timedOut) {
		this.timedOut = timedOut;
	}
}
