package com.in28minutes.webservices.songrec.integration.qdrant.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter @Builder
public class QueryVectorBuildResultDto {
  List<Float> vector;
  long embedMs;
  long upsertMs;
}
