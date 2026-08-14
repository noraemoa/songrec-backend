package com.in28minutes.webservices.songrec.evaluation.evaluator;

import com.in28minutes.webservices.songrec.evaluation.dto.EvaluateResultDto;
import com.in28minutes.webservices.songrec.evaluation.metrics.NdcgCalculator;
import com.in28minutes.webservices.songrec.evaluation.metrics.TagRelevanceCalculator;
import com.in28minutes.webservices.songrec.integration.openai.dto.TrackSearchQueryAnalysisResult;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantSearchResponse.Point;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.RerankPrepareResultDto;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.RerankedCandidate;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.SongPayload;
import com.in28minutes.webservices.songrec.service.openai.TrackSearchQueryAnalysisService;
import com.in28minutes.webservices.songrec.service.qdrant.TrackSemanticSearchService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEvaluator {

  private final TrackSearchQueryAnalysisService queryAnalysisService;
  private final TrackSemanticSearchService trackSemanticSearchService;
  private final TagRelevanceCalculator tagRelevanceCalculator;
  private final NdcgCalculator ndcgCalculator;

  public void runEvaluation() {
    List<String> queries = getQueries();

    double beforePrecisionSum = 0.0;
    double afterPrecisionSum = 0.0;
    double beforeNDCGSum = 0.0;
    double afterNDCGSum = 0.0;
    double beforeLikedSimilaritySum = 0.0;
    double afterLikedSimilaritySum = 0.0;
    for (String query : queries) {
      EvaluateResultDto result = evaluateByQuery(query, 15L);
      beforePrecisionSum += result.getBeforePrecision();
      afterPrecisionSum += result.getAfterPrecision();
      beforeNDCGSum += result.getBeforeNDCG();
      afterNDCGSum += result.getAfterNDCG();
      beforeLikedSimilaritySum += result.getBeforeLikedSimilarity();
      afterLikedSimilaritySum += result.getAfterLikedSimilarity();
    }
    log.info("beforeAvgPrecision@10={}", beforePrecisionSum / queries.size());
    log.info("afterAvgPrecision@10={}", afterPrecisionSum / queries.size());
    log.info("precisionLift={}", (afterPrecisionSum - beforePrecisionSum) / queries.size());

    log.info("beforeAvgNDCG@10={}", beforeNDCGSum / queries.size());
    log.info("afterAvgNDCG@10={}", afterNDCGSum / queries.size());
    log.info("ndcgLift={}", (afterNDCGSum - beforeNDCGSum) / queries.size());

    log.info("beforeAvgLikedSimilarity@10={}", beforeLikedSimilaritySum / queries.size());
    log.info("afterAvgLikedSimilarity@10={}", afterLikedSimilaritySum / queries.size());
    log.info("likedSimilarityLift={}",(afterLikedSimilaritySum-beforeLikedSimilaritySum)/queries.size());
  }

  public EvaluateResultDto evaluateByQuery(String query, Long userId) {
    TrackSearchQueryAnalysisResult queryResult = queryAnalysisService.analyze(query);
    Set<String> queryTags = new HashSet<>();
    queryTags.addAll(tagRelevanceCalculator.normalize(queryResult.getMood_tags()));
    queryTags.addAll(tagRelevanceCalculator.normalize(queryResult.getScene_tags()));
    queryTags.addAll(tagRelevanceCalculator.normalize(queryResult.getTexture_tags()));

    List<Float> userLikedAvgVector = trackSemanticSearchService.likedAverageVector(userId);

    //Precision@10
    List<Float> queryVector=trackSemanticSearchService.buildQueryVector(queryResult);
    List<Point> candidates = trackSemanticSearchService.searchCandidates(queryVector, 50);

    List<Point> beforeTop10 = candidates.stream().limit(10).toList();
    double beforeRerankPrecision = tagRelevanceCalculator.calculatePrecision(
        queryTags, beforeTop10);

    RerankPrepareResultDto selectCandidateResult=trackSemanticSearchService.selectRerankedCandidates(
        queryVector,candidates, userId);
    List<RerankedCandidate> selectedCandidates = selectCandidateResult.getSelectedCandidates();
    selectedCandidates.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

    List<Point> afterTop10 = selectedCandidates.stream().limit(10)
        .map(RerankedCandidate::getPoint).toList();

    double afterRerankPrecision = tagRelevanceCalculator.calculatePrecision(queryTags,
        afterTop10);

    //NDCG@10
    double beforeRerankNDCG = ndcgCalculator.calculateNDCG(
        beforeTop10, queryTags);
    double afterRerankNDCG = ndcgCalculator.calculateNDCG(afterTop10, queryTags);

    //AvgLikedSimilarity@10
    double beforeLikedSimilaritySum = 0.0;
    double afterLikedSimilaritySum = 0.0;

    int beforeCnt=0;
    for (Point beforeTop10Point : beforeTop10) {
      if (userLikedAvgVector != null && beforeTop10Point.getVector() != null) {
        beforeLikedSimilaritySum += trackSemanticSearchService.cosineSimilarity(
            beforeTop10Point.getVector(),
            userLikedAvgVector);
        beforeCnt++;
      }
    }

    int afterCnt=0;
    for(Point afterTop10Point : afterTop10) {
      if (userLikedAvgVector != null && afterTop10Point.getVector() != null) {
        afterLikedSimilaritySum += trackSemanticSearchService.cosineSimilarity(
            afterTop10Point.getVector(),
            userLikedAvgVector);
        afterCnt++;
      }
    }

    double beforeLikedSimilarity = beforeLikedSimilaritySum/beforeCnt;
    double afterLikedSimilarity = afterLikedSimilaritySum/afterCnt;

    log.info("query={}", query);
    log.info("beforeTop10={}",
        beforeTop10.stream()
            .map(p ->(SongPayload) p.getPayload())
            .map(SongPayload::getTitle)
            .toList());

    log.info("afterTop10={}",
        afterTop10.stream()
            .map(p ->(SongPayload)p.getPayload())
            .map(SongPayload::getTitle)
            .toList());
    log.info("beforePrecision@10={}", beforeRerankPrecision);
    log.info("afterPrecision@10={}", afterRerankPrecision);
    log.info("beforeNDCG@10={}", beforeRerankNDCG);
    log.info("afterNDCG@10={}", afterRerankNDCG);
    log.info("beforeLikedSimilarity@10={}",beforeLikedSimilarity);
    log.info("afterLikedSimilarity@10={}",afterLikedSimilarity);

    return EvaluateResultDto.builder()
        .beforePrecision(beforeRerankPrecision)
        .afterPrecision(afterRerankPrecision)
        .beforeNDCG(beforeRerankNDCG)
        .afterNDCG(afterRerankNDCG)
        .beforeLikedSimilarity(beforeLikedSimilarity)
        .afterLikedSimilarity(afterLikedSimilarity).build();
  }

  private List<String> getQueries() {
    return List.of(
        "비 오는 밤에 혼자 듣기 좋은 몽환적인 노래",
        "새벽 감성에 어울리는 잔잔한 노래",
        "운동할 때 듣기 좋은 강한 비트 음악",
        "드라이브할 때 듣기 좋은 신나는 노래",
        "이별 후에 듣기 좋은 슬픈 발라드",
        "공부할 때 집중 잘 되는 음악",
        "여름에 듣기 좋은 청량한 노래",
        "겨울 밤 감성적인 노래",
        "기분이 좋아지는 밝은 노래",
        "우울할 때 위로가 되는 노래",

        "여행 갈 때 듣기 좋은 설레는 노래",
        "카페에서 듣기 좋은 잔잔한 음악",
        "힙합 느낌 강한 에너지 넘치는 곡",
        "여성 보컬의 부드러운 감성 노래",
        "남성 보컬의 깊은 감성 발라드",
        "몽환적이고 신비로운 분위기의 음악",
        "강렬한 EDM 스타일 음악",
        "로파이 느낌의 편안한 음악",
        "기타 중심의 어쿠스틱 음악",
        "밤에 혼자 걸을 때 듣기 좋은 음악",

        "비 오는 날 창밖 보며 듣기 좋은 음악",
        "첫사랑 떠올리는 감성적인 노래",
        "파티 분위기 띄우는 신나는 음악",
        "차분하게 생각 정리할 때 듣는 음악",
        "스트레스 풀 때 듣기 좋은 강한 음악",
        "잔잔하지만 감정이 깊은 음악",
        "영화 OST 같은 분위기의 음악",
        "감성적인 피아노 중심 음악",
        "도시 야경 보며 듣기 좋은 음악",
        "아침에 상쾌하게 시작할 때 듣는 음악"
    );
  }


}
