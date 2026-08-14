package com.in28minutes.webservices.songrec.repository;

import com.in28minutes.webservices.songrec.domain.like.TrackLike;
import com.in28minutes.webservices.songrec.repository.projection.LikedTrackCountRow;
import com.in28minutes.webservices.songrec.repository.projection.LikedTrackRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackLikeRepository extends JpaRepository<TrackLike, Long> {

  Optional<TrackLike> findByUser_IdAndTrack_id(Long userId, Long trackId);

  boolean existsByUser_IdAndTrack_Id(Long userId, Long trackId);

  void deleteByUser_IdAndTrack_Id(Long userId, Long trackId);

  @Query("""
      select 
      t.id as trackId,
      t.name as name,
      t.artist as artist,
      t.album as album,
      t.imageUrl as imageUrl,
      tl.createdAt as createdAt
      from TrackLike tl
      join tl.track t
      where tl.user.id = :userId
      order by tl.createdAt desc
      """)
  List<LikedTrackRow> findLikedTracks(@Param("userId") Long userId);

  @Query("""
      select t.spotifyId
      from TrackLike tl
      join tl.track t
      where tl.user.id = :userId
      and t.spotifyId in :spotifyTrackIds
      """)
  List<String> findLikedSpotifyIds(@Param("userId") Long userId,@Param("spotifyTrackIds") List<String> spotifyTrackIds);

  @Query("""
          select
          count(tl)
          from TrackLike tl
          where tl.track.id = :trackId
      """)
  Long countByTrackId(Long trackId);

  @Query("""
      select
      tl.track.id as trackId,
      count(tl) as likedCount
      from TrackLike tl
      where tl.track.id in :trackIds
      group by tl.track.id
      """)
  List<LikedTrackCountRow> countLikedByTrackIds(@Param("trackIds") List<Long> trackIds);
}
