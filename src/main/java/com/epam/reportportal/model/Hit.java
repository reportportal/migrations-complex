package com.epam.reportportal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public class Hit implements Serializable {
	@JsonProperty("_index")
	private String index;

	@JsonProperty("_type")
	private String type;

	@JsonProperty("_id")
	private String id;

	@JsonProperty("_score")
	private String score;

	@JsonProperty("_source")
	private LogMessage source;

	public Hit() {
	}

	public Hit(String index, String type, String id, String score, LogMessage source) {
		this.index = index;
		this.type = type;
		this.id = id;
		this.score = score;
		this.source = source;
	}

	public String getIndex() {
		return index;
	}

	public void setIndex(String index) {
		this.index = index;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getScore() {
		return score;
	}

	public void setScore(String score) {
		this.score = score;
	}

	public LogMessage getSource() {
		return source;
	}

	public void setSource(LogMessage source) {
		this.source = source;
	}
}
