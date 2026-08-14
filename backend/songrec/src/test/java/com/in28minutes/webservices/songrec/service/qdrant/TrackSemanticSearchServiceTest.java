package com.in28minutes.webservices.songrec.service.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.in28minutes.webservices.songrec.domain.track.Track;
import com.in28minutes.webservices.songrec.domain.user.User;
import com.in28minutes.webservices.songrec.dto.request.TrackSemanticSearchItemDto;
import com.in28minutes.webservices.songrec.integration.qdrant.client.QdrantClient;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantSearchResponse;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantSearchResponse.Point;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.QdrantSearchResponse.QdrantResultResponse;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.RerankPrepareResultDto;
import com.in28minutes.webservices.songrec.integration.qdrant.dto.RerankedCandidate;
import com.in28minutes.webservices.songrec.repository.RequestTrackRepository;
import com.in28minutes.webservices.songrec.repository.TrackLikeRepository;
import com.in28minutes.webservices.songrec.repository.TrackRepository;
import com.in28minutes.webservices.songrec.repository.UserRepository;
import com.in28minutes.webservices.songrec.repository.projection.LikedTrackCountRow;
import com.in28minutes.webservices.songrec.repository.projection.RequestTrackFeedbackRow;
import com.in28minutes.webservices.songrec.service.RequestTrackService;
import com.in28minutes.webservices.songrec.service.TrackService;
import com.in28minutes.webservices.songrec.service.openai.EmbeddingService;
import com.in28minutes.webservices.songrec.service.openai.TrackSearchQueryAnalysisService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TrackSemanticSearchService의 개인화 Reranking 로직(selectRerankedCandidates, rerank) 단위 테스트.
 *
 * OpenAI/Qdrant 외부 호출은 QdrantClient를 목(mock) 처리해서 배제하고,
 * finalScore 계산식(0.60*qdrantScore + 0.10*profileScore + 0.10*likedScore
 * + 0.05*popularityScore + 0.15*adjustedFeedbackScore)과
 * 정렬/limit/중복 필터링 동작만 검증한다.
 *
 * 주의: selectRerankedCandidates()가 반환하는 RerankPrepareResultDto의
 * selectedCandidates 필드가 builder에서 세팅되지 않으면 이 테스트들은 전부 실패한다.
 * (실제로 프로덕션 코드에서 이 필드 세팅이 누락되어 있었음 — .selectedCandidates(selectedCandidates) 추가 필요)
 */
@ExtendWith(MockitoExtension.class)
public class TrackSemanticSearchServiceTest {

  @InjectMocks
  private TrackSemanticSearchService trackSemanticSearchService;

  @Mock
  private TrackSearchQueryAnalysisService trackSearchQueryAnalysisService;
  @Mock
  private EmbeddingService embeddingService;
  @Mock
  private QdrantClient qdrantClient;
  @Mock
  private TrackRepository trackRepository;
  @Mock
  private TrackLikeRepository trackLikeRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private TrackService trackService;
  @Mock
  private RequestTrackRepository requestTrackRepository;
  @Mock
  private RequestTrackService requestTrackService;

  @Test
  @DisplayName("rerank()는 finalScore 내림차순으로 정렬한다")
  void rerank_sortsByFinalScoreDescending() {
    Track trackA = Track.builder().id(1L).name("A").build();
    Track trackB = Track.builder().id(2L).name("B").build();
    Track trackC = Track.builder().id(3L).name("C").build();

    RerankedCandidate candidateA = new RerankedCandidate(null, trackA, 0.82);
    RerankedCandidate candidateB = new RerankedCandidate(null, trackB, 0.73);
    RerankedCandidate candidateC = new RerankedCandidate(null, trackC, 0.91);

    List<TrackSemanticSearchItemDto> result = trackSemanticSearchService.rerank(
        new ArrayList<>(List.of(candidateA, candidateB, candidateC)), 10);

    assertThat(result)
        .extracting(TrackSemanticSearchItemDto::getTrackId)
        .containsExactly(3L, 1L, 2L);
  }

  @Test
  @DisplayName("rerank()는 limit 개수만큼만 반환한다")
  void rerank_appliesLimit() {
    Track trackA = Track.builder().id(1L).name("A").build();
    Track trackB = Track.builder().id(2L).name("B").build();
    Track trackC = Track.builder().id(3L).name("C").build();

    RerankedCandidate candidateA = new RerankedCandidate(null, trackA, 0.82);
    RerankedCandidate candidateB = new RerankedCandidate(null, trackB, 0.73);
    RerankedCandidate candidateC = new RerankedCandidate(null, trackC, 0.91);

    List<TrackSemanticSearchItemDto> result = trackSemanticSearchService.rerank(
        new ArrayList<>(List.of(candidateA, candidateB, candidateC)), 2);

    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(TrackSemanticSearchItemDto::getTrackId)
        .containsExactly(3L, 1L);
  }

  @Test
  @DisplayName("rerank()는 selectedCandidates가 null이면 빈 리스트를 반환한다")
  void rerank_returnsEmptyListWhenCandidatesIsNull() {
    List<TrackSemanticSearchItemDto> result = trackSemanticSearchService.rerank(null, 10);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("qdrant 유사도가 같으면 좋아요 수(인기도)가 많은 트랙의 finalScore가 더 높다")
  void selectRerankedCandidates_weighsPopularity() {
    Long userId = 1L;
    List<Float> queryVector = List.of(1f, 0f);

    Point popularPoint = pointOf(10L, 0.8, List.of(1f, 0f));
    Point unpopularPoint = pointOf(20L, 0.8, List.of(0f, 1f));

    Track popularTrack = Track.builder().id(10L).name("popular").build();
    Track unpopularTrack = Track.builder().id(20L).name("unpopular").build();

    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).build()));
    when(trackLikeRepository.findLikedTracks(userId)).thenReturn(Collections.emptyList());
    when(qdrantClient.searchQuery(queryVector, 30)).thenReturn(emptySearchResponse());
    when(trackRepository.findAllByIdIn(any()))
        .thenReturn(List.of(popularTrack, unpopularTrack));
    when(requestTrackRepository.findFeedbackRowByRequestIdsAndTrackIds(any(), any()))
        .thenReturn(Collections.<RequestTrackFeedbackRow>emptyList());
    // 인기도는 배치 조회(GROUP BY) 결과로 온다. unpopularTrack(20L)은 좋아요가 0개라
    // 실제 GROUP BY 쿼리라면 아예 행이 없으므로 목록에서 제외한다.
    // (mock 생성을 when() 인자 밖에서 먼저 끝내야 UnfinishedStubbingException이 안 난다)
    LikedTrackCountRow popularCountRow = likedCountRowOf(10L, 100L);
    when(trackLikeRepository.countLikedByTrackIds(any()))
        .thenReturn(List.of(popularCountRow));

    RerankPrepareResultDto prepareResult = trackSemanticSearchService.selectRerankedCandidates(
        queryVector, List.of(popularPoint, unpopularPoint), userId);
    List<RerankedCandidate> results = prepareResult.getSelectedCandidates();

    double popularScore = scoreOf(results, 10L);
    double unpopularScore = scoreOf(results, 20L);

    // 0.60*0.8 + 0.05*normalizePopularity(100=1.0) = 0.53
    assertThat(popularScore).isCloseTo(0.53, within(0.001));
    // 0.60*0.8 + 0.05*normalizePopularity(0=0.0) = 0.48
    assertThat(unpopularScore).isCloseTo(0.48, within(0.001));
    assertThat(popularScore).isGreaterThan(unpopularScore);
  }

  @Test
  @DisplayName("코사인 유사도 0.95를 초과하는 후보는 중복으로 간주해 제외한다")
  void selectRerankedCandidates_filtersNearDuplicateCandidates() {
    Long userId = 1L;
    List<Float> queryVector = List.of(1f, 0f);
    List<Float> sameVector = List.of(1f, 0f);

    Point first = pointOf(1L, 0.9, sameVector);
    Point duplicate = pointOf(2L, 0.85, sameVector);

    Track track1 = Track.builder().id(1L).name("first").build();
    Track track2 = Track.builder().id(2L).name("duplicate").build();

    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).build()));
    when(trackLikeRepository.findLikedTracks(userId)).thenReturn(Collections.emptyList());
    when(qdrantClient.searchQuery(queryVector, 30)).thenReturn(emptySearchResponse());
    when(trackRepository.findAllByIdIn(any())).thenReturn(List.of(track1, track2));
    when(requestTrackRepository.findFeedbackRowByRequestIdsAndTrackIds(any(), any()))
        .thenReturn(Collections.<RequestTrackFeedbackRow>emptyList());
    when(trackLikeRepository.countLikedByTrackIds(any())).thenReturn(Collections.emptyList());

    RerankPrepareResultDto prepareResult = trackSemanticSearchService.selectRerankedCandidates(
        queryVector, List.of(first, duplicate), userId);
    List<RerankedCandidate> results = prepareResult.getSelectedCandidates();

    // duplicate(2L)는 filteredPoints의 first(1L)와 코사인 유사도 1.0(>0.95)이라 제외되어야 한다
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getTrack().getId()).isEqualTo(1L);
  }

  @Test
  @DisplayName("벡터가 없거나 DB에서 트랙을 찾을 수 없는 후보는 결과에서 제외한다")
  void selectRerankedCandidates_skipsInvalidCandidates() {
    Long userId = 1L;
    List<Float> queryVector = List.of(1f, 0f);

    Point noVectorPoint = pointOf(1L, 0.9, null);
    Point noTrackPoint = pointOf(99L, 0.9, List.of(1f, 0f));
    Point validPoint = pointOf(2L, 0.9, List.of(0f, 1f));

    Track validTrack = Track.builder().id(2L).name("valid").build();

    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).build()));
    when(trackLikeRepository.findLikedTracks(userId)).thenReturn(Collections.emptyList());
    when(qdrantClient.searchQuery(queryVector, 30)).thenReturn(emptySearchResponse());
    // 99L(noTrackPoint)에 대응하는 Track은 일부러 반환하지 않는다 (DB에는 없지만 Qdrant엔 남아있는 상황 재현)
    when(trackRepository.findAllByIdIn(any())).thenReturn(List.of(validTrack));
    when(requestTrackRepository.findFeedbackRowByRequestIdsAndTrackIds(any(), any()))
        .thenReturn(Collections.<RequestTrackFeedbackRow>emptyList());
    when(trackLikeRepository.countLikedByTrackIds(any())).thenReturn(Collections.emptyList());

    RerankPrepareResultDto prepareResult = trackSemanticSearchService.selectRerankedCandidates(
        queryVector, List.of(noVectorPoint, noTrackPoint, validPoint), userId);
    List<RerankedCandidate> results = prepareResult.getSelectedCandidates();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getTrack().getId()).isEqualTo(2L);
  }

  private double scoreOf(List<RerankedCandidate> candidates, Long trackId) {
    return candidates.stream()
        .filter(c -> c.getTrack().getId().equals(trackId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("trackId=" + trackId + " 후보를 찾지 못했습니다."))
        .getFinalScore();
  }

  private Point pointOf(Long id, double score, List<Float> vector) {
    Point point = new Point();
    point.setId(id);
    point.setScore(score);
    point.setVector(vector);
    return point;
  }

  private LikedTrackCountRow likedCountRowOf(Long trackId, Long likedCount) {
    LikedTrackCountRow row = mock(LikedTrackCountRow.class);
    when(row.getTrackId()).thenReturn(trackId);
    when(row.getLikedCount()).thenReturn(likedCount);
    return row;
  }

  private QdrantSearchResponse emptySearchResponse() {
    QdrantSearchResponse response = new QdrantSearchResponse();
    QdrantResultResponse result = new QdrantResultResponse();
    result.setPoints(Collections.emptyList());
    response.setResult(result);
    return response;
  }
}