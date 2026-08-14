package com.in28minutes.webservices.songrec.controller;

import static com.in28minutes.webservices.songrec.global.util.TextNormalizer.normalize;

import com.in28minutes.webservices.songrec.config.security.JwtPrincipal;
import com.in28minutes.webservices.songrec.domain.keyword.Keyword;
import com.in28minutes.webservices.songrec.domain.request.Request;
import com.in28minutes.webservices.songrec.domain.request.RequestKeyword;
import com.in28minutes.webservices.songrec.domain.request.RequestTrack;
import com.in28minutes.webservices.songrec.domain.request.RequestTrackRating;
import com.in28minutes.webservices.songrec.domain.track.Track;
import com.in28minutes.webservices.songrec.dto.request.RequestCreateRequestDto;
import com.in28minutes.webservices.songrec.dto.request.RequestTrackRatingRequestDto;
import com.in28minutes.webservices.songrec.dto.request.TrackCreateRequestDto;
import com.in28minutes.webservices.songrec.dto.response.*;
import com.in28minutes.webservices.songrec.dto.response.keyword.KeywordResponseDto;
import com.in28minutes.webservices.songrec.dto.response.request.RecommendedTrackResponseDto;
import com.in28minutes.webservices.songrec.dto.response.request.RequestFeedItemDto;
import com.in28minutes.webservices.songrec.dto.response.request.RequestKeywordResponseDto;
import com.in28minutes.webservices.songrec.dto.response.request.RequestResponseDto;
import com.in28minutes.webservices.songrec.dto.response.request.RequestSummaryResponseDto;
import com.in28minutes.webservices.songrec.dto.response.request.RequestTrackRatingResponseDto;
import com.in28minutes.webservices.songrec.dto.response.request.RequestTrackResponseDto;
import com.in28minutes.webservices.songrec.dto.response.track.RecommendedTracksResponseDto;
import com.in28minutes.webservices.songrec.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/requests")
public class RequestController {

  private final RequestService requestService;
  private final RequestTrackService requestTrackService;
  private final RequestKeywordService requestKeywordService;
  private final RequestFeedService requestFeedService;
  private final KeywordService keywordService;
  private final TrackService trackService;

  // requests
  @PostMapping
  public ResponseEntity<RequestResponseDto> createRequest(
      @Valid @RequestBody RequestCreateRequestDto requestDto,
      @AuthenticationPrincipal JwtPrincipal principal) {
    RequestResponseDto request = requestService.createRequest(requestDto, principal.userId());
    return ResponseEntity.status(HttpStatus.CREATED).body(request);
  }

  @PatchMapping("/{requestId}")
  public RequestResponseDto updateRequest(@Valid @RequestBody RequestCreateRequestDto requestDto,
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId) {
    Request request = requestService.updateRequest(requestDto, principal.userId(), requestId);
    List<String> keywords = requestService.readKeywords(request.getPromptKeywordsJson());
    return RequestResponseDto.from(request, keywords.stream().map(k -> Keyword.builder()
        .rawText(k)
        .normalizedText(normalize(k)).build()).toList());
  }

  @GetMapping("/feed")
  public List<RequestFeedItemDto> getFeed(@RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return requestFeedService.getFeed(page, size);
  }

  @GetMapping("/me")
  public List<RequestSummaryResponseDto> getMyRequests(
      @AuthenticationPrincipal JwtPrincipal principal) {
    List<Request> requestList = requestService.getRequestsByUserId(principal.userId());

    return requestList.stream().map(RequestSummaryResponseDto::from).toList();
  }

  @GetMapping("/{requestId}")
  public RequestResponseDto getRequestFeed(@PathVariable @NotNull @Positive Long requestId) {

    Request request = requestService.getRequestFeed(requestId);
    List<String> keywords = requestService.readKeywords(request.getPromptKeywordsJson());

    return RequestResponseDto.from(request, keywords.stream().map(k -> Keyword.builder()
        .rawText(k)
        .normalizedText(normalize(k)).build()).toList());
  }

  @DeleteMapping("/{requestId}")
  public ResponseEntity<Void> deleteRequest(@AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId) {
    requestService.deleteRequest(principal.userId(), requestId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/admin/{requestId}")
  public ResponseEntity<Void> deleteRequestAdmin(
      @PathVariable @NotNull @Positive Long requestId) {
    requestService.deleteRequestAdmin(requestId);
    return ResponseEntity.noContent().build();
  }

  // tracks
  @GetMapping("/{requestId}/tracks")
  public List<RecommendedTrackResponseDto> getTracksByRequest(@AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId) {
    return requestTrackService.getTracksByRequest(principal.userId(), requestId);
  }

  // track에 추가되지 않은 spotify track을 track테이블에 먼저 추가하고 플리에 해당 track 저장
  @PostMapping("/{requestId}/tracks")
  public ResponseEntity<RequestTrackResponseDto> addSpotifyTrackByRequest(
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId,
      @RequestBody @Valid TrackCreateRequestDto dto) {
    RequestTrack rt = requestTrackService.addSpotifyTrackToRequest(requestId, dto);

    try {
      trackService.ensureTrackIndexed(rt.getTrack(), dto);
    } catch (Exception e) {
      e.printStackTrace();
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(RequestTrackResponseDto.from(rt));
  }

  //요청에 트랙 직접 추가
  // track 테이블에 있는 노래만 이 api 쓸 수 있음.
  @PostMapping("/{requestId}/tracks/{trackId}")
  public ResponseEntity<RequestTrackResponseDto> addTrackByRequest(
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId,
      @PathVariable @NotNull @Positive Long trackId) {
    RequestTrack rt = requestTrackService.addTrackByRequest(requestId, trackId);
    return ResponseEntity.status(HttpStatus.CREATED).body(RequestTrackResponseDto.from(rt));
  }

  @PutMapping("/{requestId}/tracks/{trackId}/rating")
  public ResponseEntity<RequestTrackRatingResponseDto> rateRequestTrackRating(
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId,
      @PathVariable @NotNull @Positive Long trackId,
      @Valid @RequestBody RequestTrackRatingRequestDto ratingDto) {
    RequestTrackRating requestTrackRating = requestTrackService.rateTrack(
        principal.userId(), requestId, trackId, ratingDto.getRating());
    return ResponseEntity.ok(RequestTrackRatingResponseDto.from(requestTrackRating));
  }

  @GetMapping("/{requestId}/tracks/{trackId}")
  public RequestTrackRatingResponseDto getRequestTrackRating(
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId,
      @PathVariable @NotNull @Positive Long trackId) {
    RequestTrackRating rtr = requestTrackService.getRequestTrackRating(principal.userId(),
        requestId, trackId);
    return RequestTrackRatingResponseDto.from(rtr);
  }

  @DeleteMapping("/{requestId}/tracks/{trackId}")
  public ResponseEntity<Void> deleteTrackByRequest(@AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId,
      @PathVariable @NotNull @Positive Long trackId) {

    requestTrackService.deleteTrack(principal.userId(), requestId, trackId);
    return ResponseEntity.noContent().build();
  }

  //keywords
  @PostMapping("/{requestId}/keywords/{keywordId}")
  public ResponseEntity<RequestKeywordResponseDto> addKeywordByRequest(
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId,
      @PathVariable @NotNull @Positive Long keywordId) {
    Request request = requestService.getRequestFeed(requestId);
    Keyword keyword = keywordService.getKeyword(keywordId);
    RequestKeyword rk = requestKeywordService.addKeywordByRequest(request,
        keyword);
    // 키워드를 추가했을 때 키워드와 연결된 track을 해당 request track에 추가
//        List<Track> tracks = keywordTrackService.getTracksByKeyword(keywordId);
//
//        tracks.forEach(
//                track -> requestTrackService.addTrackByRequest(userId, requestId, track.getId()));

    return ResponseEntity.status(HttpStatus.CREATED).body(RequestKeywordResponseDto.from(rk));
  }

  @GetMapping("/{requestId}/keywords")
  public List<KeywordResponseDto> getKeywordsByRequest(
      @PathVariable @NotNull @Positive Long requestId) {
    List<Keyword> keywordsList = requestKeywordService.getKeywordsByRequest(requestId);
    return keywordsList.stream().map(KeywordResponseDto::from).toList();
  }

  @DeleteMapping("/{requestId}/keywords/{keywordId}")
  public ResponseEntity<Void> deleteKeywordByRequest(
      @PathVariable @NotNull @Positive Long requestId,
      @PathVariable @NotNull @Positive Long keywordId) {
    requestKeywordService.deleteKeywordByRequestId(requestId, keywordId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/{requestId}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<RequestSummaryResponseDto> uploadThumbnail(
      @AuthenticationPrincipal JwtPrincipal principal,
      @PathVariable @NotNull @Positive Long requestId, @RequestParam("file") MultipartFile file)
      throws IOException {
    Request request = requestService.uploadThumbnail(principal.userId(), requestId, file);
    return ResponseEntity.ok(RequestSummaryResponseDto.from(request));
  }
}
