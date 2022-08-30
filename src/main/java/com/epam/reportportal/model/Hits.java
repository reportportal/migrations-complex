package com.epam.reportportal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

public class Hits implements Serializable {
	@JsonProperty("hits")
	private List<Hit> hits;

	@JsonProperty("max_score")
	private String maxScore;

	public Hits() {
	}

	public Hits(List<Hit> hits, String maxScore) {
		this.hits = hits;
		this.maxScore = maxScore;
	}

	public List<Hit> getHits() {
		return hits;
	}

	public void setHits(List<Hit> hits) {
		this.hits = hits;
	}

	public String getMaxScore() {
		return maxScore;
	}

	public void setMaxScore(String maxScore) {
		this.maxScore = maxScore;
	}
}
