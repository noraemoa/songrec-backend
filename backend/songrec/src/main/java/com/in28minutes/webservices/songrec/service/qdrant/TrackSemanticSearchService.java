package com.in28minutes.webservices.songrec.service.qdrant;

import com.in28minutes.webservices.songrec.domain.track.Track;
import com.in28minutes.webservices.songrec.domain.user.User;
import com.in28minutes.webservices.songrec.dto.request.TrackSemanticSearchItemDto;
import com.in28minutes.webservices.songrec.integration.openai.dto.TrackSearchQueryAnalysisResult;
import com.in28minutes.webservices.songrec.integration.qdrant.client.QdrantClient;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantPoint;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantRetrieveResponse;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantSearchResponse;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantSearchResponse.Point;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QueryEmbeddingPayload;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QueryVectorBuildResultDto;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.RerankPrepareResultDto;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.RerankedCandidate;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.SongPayload;
import com.in28minutes.webservices.songrec.repository.RequestTrackRepository;
import com.in28minutes.webservices.songrec.repository.TrackLikeRepository;
import com.in28minutes.webservices.songrec.repository.TrackRepository;
import com.in28minutes.webservices.songrec.repository.UserRepository;
import com.in28minutes.webservices.songrec.repository.projection.LikedTrackCountRow;
import com.in28minutes.webservices.songrec.repository.projection.LikedTrackRow;
import com.in28minutes.webservices.songrec.repository.projection.RequestTrackFeedbackRow;
import com.in28minutes.webservices.songrec.service.RequestTrackService;
import com.in28minutes.webservices.songrec.service.TrackService;
import com.in28minutes.webservices.songrec.service.openai.EmbeddingService;
import com.in28minutes.webservices.songrec.service.openai.TrackSearchQueryAnalysisService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackSemanticSearchService {

  private final TrackSearchQueryAnalysisService trackSearchQueryAnalysisService;
  private final EmbeddingService embeddingService;
  private final QdrantClient qdrantClient;
  private final TrackRepository trackRepository;
  private final TrackLikeRepository trackLikeRepository;
  private final UserRepository userRepository;
  private final TrackService trackService;
  private final RequestTrackRepository requestTrackRepository;
  private final RequestTrackService requestTrackService;

  public List<TrackSemanticSearchItemDto> search(Long userId, Long requestId, String query,
      int limit) {
    long totalStart = System.nanoTime();

    // query 분석
    long t1 = System.nanoTime();
    TrackSearchQueryAnalysisResult analysis =
        trackSearchQueryAnalysisService.analyze(query);
    long analyzeMs = (System.nanoTime() - t1) / 1000000;

    // qdrant 후보
    long t2 = System.nanoTime();
    QueryVectorBuildResultDto queryVectorBuildResult = buildQueryVectorAndUpsert(requestId,
        analysis);
    List<Float> queryVector = queryVectorBuildResult.getVector();
    long queryEmbeddingMs = (System.nanoTime() - t2) / 1000000;

    long t3 = System.nanoTime();
    List<Point> response = searchCandidates(queryVector, 50);
    long candidateSearchMs = (System.nanoTime() - t3) / 1000000;

    // 재정렬
    if (response == null || response.isEmpty()) {
      long totalMs = (System.nanoTime() - totalStart) / 1000000;
      log.info(
          "searchPerf requestId={},analyzeMs={}, queryEmbeddingMs={},candidateSearchMs={},totalMs={}",
          requestId, analyzeMs, queryEmbeddingMs, candidateSearchMs, totalMs);
      return null;
    }

    long t4 = System.nanoTime();
    RerankPrepareResultDto selectCandidateResult = selectRerankedCandidates(
        queryVector, response, userId);
    List<RerankedCandidate> selectedCandidates = selectCandidateResult.getSelectedCandidates();
    long rerankPrepareMs = (System.nanoTime() - t4) / 1000000;

    long t5 = System.nanoTime();
    List<TrackSemanticSearchItemDto> result = rerank(selectedCandidates, limit);
    long rerankMs = (System.nanoTime() - t5) / 1000000;

    long totalMs = (System.nanoTime() - totalStart) / 1000000;

    log.info(
        "PERF_SUMMARY requestId={} totalMs={} analyzeMs={} embedMs={} upsertMs={} candidateSearchMs={} rerankPrepareMs={} rerankMs={} "
            + "likedVectorMs={} profileVectorMs={} similarQuerySearchMs={} batchMs={} selectCandidateMs={} "
            + "popularityTotalMs={} feedbackTotalMs={} similarityFilterTotalMs={}",
        requestId,
        totalMs,
        analyzeMs,
        queryVectorBuildResult.getEmbedMs(),
        queryVectorBuildResult.getUpsertMs(),
        candidateSearchMs,
        rerankPrepareMs,
        rerankMs,
        selectCandidateResult.getLikedVectorMs(),
        selectCandidateResult.getProfileVectorMs(),
        selectCandidateResult.getSimilarQuerySearchMs(),
        selectCandidateResult.getBatchMs(),
        selectCandidateResult.getSelectCandidateMs(),
        selectCandidateResult.getTotalPopularityMs(),
        selectCandidateResult.getTotalFeedbackMs(),
        selectCandidateResult.getTotalSimilarTrackFilterMs()
    );
    return result;
  }

  private String buildSearchText(TrackSearchQueryAnalysisResult result) {
    return String.format(
        "mood: %s, scene: %s, texture: %s, genre: %s. description: %s.",
        safeJoin(result.getMood_tags()),
        safeJoin(result.getScene_tags()),
        safeJoin(result.getTexture_tags()),
        safeJoin(result.getGenre_tags()),
        nullToEmpty(result.getShort_description())
    ).trim().replaceAll("\\s+", " ");
  }

  private QueryVectorBuildResultDto buildQueryVectorAndUpsert(Long requestId,
      TrackSearchQueryAnalysisResult queryAnalysisResult) {
    long tEmbed = System.nanoTime();
    String searchText = buildSearchText(queryAnalysisResult);
    List<Float> vector = embeddingService.embedText(searchText);
    long embedMs = (System.nanoTime() - tEmbed) / 1000000;

    long tUpsert = System.nanoTime();
    QueryEmbeddingPayload payload = QueryEmbeddingPayload.builder()
        .requestId(requestId)
        .queryText(searchText).build();

    QdrantPoint point = QdrantPoint.builder()
        .id(requestId)
        .vector(vector)
        .payload(payload).build();
    qdrantClient.upsertQueryEmbeddingPoint(point);
    long upsertMs = (System.nanoTime() - tUpsert) / 1000000;
    return QueryVectorBuildResultDto.builder()
        .vector(vector)
        .embedMs(embedMs)
        .upsertMs(upsertMs).build();
  }

  public List<Point> searchCandidates(List<Float> vector,
      int limit) {
    // qdrant 후보
    QdrantSearchResponse response = qdrantClient.searchSong(vector, limit);
//    response.getResult().getPoints().stream().map(r -> (SongPayload) r.getPayload())
//        .forEach(p -> log.info("후보곡:{}", p.getTitle()));

    return response.getResult().getPoints();
  }

  public RerankPrepareResultDto selectRerankedCandidates(List<Float> queryVector,
      List<Point> response, Long userId) {
    long tLikedVector = System.nanoTime();
    User user = userRepository.findById(userId).orElse(null);

    // 좋아하는 track vector들의 평균
    List<Float> likedAverageVector = null;
    try {
      likedAverageVector = likedAverageVector(userId);
    } catch (Exception e) {
      log.warn("Failed to load likedAverageVector for userId={}", userId, e);
      likedAverageVector = null;
    }
    long likedVectorMs = (System.nanoTime() - tLikedVector) / 1000000;

    long tProfile = System.nanoTime();
    // 프로필 vector
    List<Float> profileVector = null;
    try {
      if (user != null && user.getProfileVectorRef() != null) {
        QdrantRetrieveResponse profileResponse =
            qdrantClient.retrieveUserProfilePoints(List.of(user.getProfileVectorRef()));

        if (profileResponse != null
            && profileResponse.getResult() != null
            && !profileResponse.getResult().isEmpty()
            && profileResponse.getResult().get(0).getVector() != null) {
          profileVector = profileResponse.getResult().get(0).getVector();
        }
      }
    } catch (Exception e) {
      profileVector = null;
    }
    long profileVectorMs = (System.nanoTime() - tProfile) / 1000000;

    long tSimilar = System.nanoTime();
    // 과거 유사한 query vector들의 평균
    QdrantSearchResponse queryResponse = qdrantClient.searchQuery(queryVector, 30);
    List<Long> queryRequestIds = queryResponse.getResult().getPoints().stream().map(Point::getId)
        .toList();
    long similarQuerySearchMs = (System.nanoTime() - tSimilar) / 1000000;

    long tBatch = System.nanoTime();
    List<Long> trackIds = response.stream().map(Point::getId).toList();
    Map<Long, Track> trackMap = trackRepository.findAllByIdIn(trackIds).stream().collect(
        Collectors.toMap(Track::getId, Function.identity()));

    // requestId로 score(sim) 찾기
    Map<Long, Double> requestSimilarityMap = queryResponse.getResult().getPoints().stream().collect(
        Collectors.toMap(Point::getId, p -> p.getScore() == null ? 0.0 : p.getScore()));
    // trackId로 row 찾기
    Map<Long, List<RequestTrackFeedbackRow>> feedbackRowByTrackId = requestTrackRepository.findFeedbackRowByRequestIdsAndTrackIds(
            queryRequestIds, trackIds).stream()
        .collect(Collectors.groupingBy(RequestTrackFeedbackRow::getTrackId));

    Map<Long, Long> totalTrackLikedCount = trackLikeRepository.countLikedByTrackIds(trackIds)
        .stream()
        .collect(
            Collectors.toMap(LikedTrackCountRow::getTrackId, LikedTrackCountRow::getLikedCount));
    long batchMs = (System.nanoTime() - tBatch) / 1000000;

    long totalSimilarTrackFilterMs = 0;
    long totalPopularityMs = 0;
    long totalFeedbackMs = 0;
    long tSelectCandidates = System.nanoTime();
    List<RerankedCandidate> selectedCandidates = new ArrayList<>();
    List<Point> filteredPoints = new ArrayList<>();
    for (Point candidate : response) {
      if (candidate.getVector() == null) {
        continue;
      }

      long tSimilarTrackFilter = System.nanoTime();
      // 유사한 트랙들 걸러내기
      boolean tooSimilar = false;
      for (Point filteredPoint : filteredPoints) {
        if (filteredPoint.getVector() == null) {
          continue;
        }

        double sim = cosineSimilarity(candidate.getVector(), filteredPoint.getVector());
        if (sim > 0.95) {
          tooSimilar = true;
          break;
        }
      }
      if (tooSimilar) {
        continue;
      }
      totalSimilarTrackFilterMs += (System.nanoTime() - tSimilarTrackFilter) / 1000000;

      double qdrantScore = candidate.getScore() == null ? 0.0 : candidate.getScore();

      Track track = trackMap.get(candidate.getId());
      if (track == null) {
        continue;
      }
      Long trackId = track.getId();

      long tPopularity = System.nanoTime();
      // 인기도 계산
      Long likedCount = totalTrackLikedCount.get(trackId);
      double popularityScore = normalizePopularity(likedCount);
      totalPopularityMs += (System.nanoTime() - tPopularity) / 1000000;

      // 사용자 선호 유사도 계산
      double likedScore = 0.0;
      if (likedAverageVector != null && candidate.getVector() != null) {
        likedScore = cosineSimilarity(candidate.getVector(), likedAverageVector);
      }

      // 사용자 프로필(취향) 유사도 계산
      double profileScore = 0.0;
      if (profileVector != null) {
        profileScore = cosineSimilarity(candidate.getVector(), profileVector);
      }

      long tFeedback = System.nanoTime();
      // 과거 별점 피드백 반영
      double adjustedFeedbackScore = 0.0;
      List<RequestTrackFeedbackRow> feedbackRows = feedbackRowByTrackId.get(trackId);
      double weightedSum = 0.0;
      double weightSum = 0.0;
      if (feedbackRows != null) {
        for (RequestTrackFeedbackRow feedbackRow : feedbackRows) {
          Double sim = requestSimilarityMap.get(feedbackRow.getRequestId());
          if (sim == null || sim <= 0.0) {
            continue;
          }

          double avgRating = feedbackRow.getAvgRating();
          double normalized = (avgRating - 3.0) / 2.0;
          double confidence = Math.min(1.0,
              Math.log(1 + feedbackRow.getRatingCount()) / Math.log(20));
          double adjustedFeedback = normalized * confidence;

          weightedSum += (sim * adjustedFeedback);
          weightSum += sim;
        }
      }
      totalFeedbackMs += (System.nanoTime() - tFeedback) / 1000000;

      if (weightSum > 0.0) {
        adjustedFeedbackScore = weightedSum / weightSum;
      }

      double finalScore =
          0.60 * qdrantScore + 0.10 * profileScore + 0.10 * likedScore + 0.05 * popularityScore
              + 0.15 * adjustedFeedbackScore;
      filteredPoints.add(candidate);
      selectedCandidates.add(new RerankedCandidate(candidate, track, finalScore));
    }
    long selectCandidateMs = (System.nanoTime() - tSelectCandidates) / 1000000;

    return RerankPrepareResultDto.builder()
        .totalFeedbackMs(totalFeedbackMs)
        .totalPopularityMs(totalPopularityMs)
        .totalSimilarTrackFilterMs(totalSimilarTrackFilterMs)
        .likedVectorMs(likedVectorMs)
        .profileVectorMs(profileVectorMs)
        .similarQuerySearchMs(similarQuerySearchMs)
        .batchMs(batchMs)
        .selectedCandidates(selectedCandidates)
        .selectCandidateMs(selectCandidateMs).build();
  }


  public List<TrackSemanticSearchItemDto> rerank(List<RerankedCandidate> selectedCandidates,
      int limit) {
    List<TrackSemanticSearchItemDto> results = new ArrayList<>();

    if (selectedCandidates == null) {
      return results;
    }
    selectedCandidates.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
    selectedCandidates.stream()
        .limit(limit)
        .forEach(
            c -> results.add(TrackSemanticSearchItemDto.from(c.getTrack(), c.getFinalScore())));

    return results;
  }


  public List<Float> buildQueryVector(TrackSearchQueryAnalysisResult queryAnalysisResult) {
    String searchText = buildSearchText(queryAnalysisResult);
    return embeddingService.embedText(searchText);
  }

  private double normalizePopularity(Long likedCount) {
    if (likedCount == null || likedCount <= 0) {
      return 0.0;
    }

    // 임시 기준: 좋아요 100개 정도면 거의 1.0에 가깝게
    double normalized = Math.log(1 + likedCount) / Math.log(101);
    return Math.min(1.0, normalized);
  }

  public List<Float> likedAverageVector(Long userId) {
    List<Long> likedTracks = trackLikeRepository.findLikedTracks(userId).stream()
        .map(LikedTrackRow::getTrackId)
        .toList();

    if (likedTracks.isEmpty()) {
      return null;
    }

    List<QdrantPoint> likedPoints = qdrantClient.retrievePoints(likedTracks).getResult();

    log.info("likedTrackCount={}", likedTracks.size());
    log.info("likedPointCount={}", likedPoints.size());

    List<List<Float>> trackVectors = likedPoints.stream()
        .map(QdrantPoint::getVector)
        .filter(Objects::nonNull)
        .toList();

    if (trackVectors.isEmpty()) {
      return null;
    }

    return averageVectors(trackVectors);
  }

  public List<Float> averageVectors(List<List<Float>> vectors) {
    if (vectors == null || vectors.isEmpty()) {
      throw new IllegalArgumentException("vectors is empty");
    }

    int dimension = vectors.get(0).size();
    double[] sums = new double[dimension];

    for (List<Float> vector : vectors) {
      if (vector == null || vector.size() != dimension) {
        throw new IllegalArgumentException("vector size mismatch");
      }

      for (int i = 0; i < dimension; i++) {
        sums[i] += vector.get(i);
      }
    }

    List<Float> average = new ArrayList<>(dimension);
    int count = vectors.size();

    for (int i = 0; i < dimension; i++) {
      average.add((float) (sums[i] / count));
    }

    return average;
  }

  public double cosineSimilarity(List<Float> a, List<Float> b) {
    if (a.size() != b.size()) {
      throw new IllegalArgumentException("Vector size mismatch");
    }

    double dotProduct = 0.0;
    double normA = 0.0;
    double normB = 0.0;

    for (int i = 0; i < a.size(); i++) {
      double va = a.get(i);
      double vb = b.get(i);

      dotProduct += va * vb;
      normA += va * va;
      normB += vb * vb;
    }

    if (normA == 0 || normB == 0) {
      return 0.0;
    }

    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
  }

//  private Long extractTrackId(QdrantSearchResponse.Point point) {
//    if (point.getPayload() != null && point.getPayload().getTrackId() != null) {
//      return point.getPayload().getTrackId();
//    }
//
//    Object id = point.getId();
//    if (id instanceof Number number) {
//      return number.longValue();
//    }
//
//    try {
//      return Long.parseLong(String.valueOf(id));
//    } catch (Exception e) {
//      return null;
//    }
//  }

  private String safeJoin(List<String> values) {
    return values == null || values.isEmpty() ? "" : String.join(", ", values);
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
