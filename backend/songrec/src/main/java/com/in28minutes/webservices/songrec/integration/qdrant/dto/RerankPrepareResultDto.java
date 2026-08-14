package com.in28minutes.webservices.songrec.integration.qdrant.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter @Builder
public class RerankPrepareResultDto {
  long totalSimilarTrackFilterMs;
  long totalPopularityMs;
  long totalFeedbackMs;
  long likedVectorMs;
  long profileVectorMs;
  long similarQuerySearchMs;
  long batchMs;
  long selectCandidateMs;
  List<RerankedCandidate> selectedCandidates;
}
