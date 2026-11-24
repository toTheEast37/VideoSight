package com.vibeteaching.qa.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VideoVectorMapper {
    @Select("""
            <script>
            SELECT content_key, embedding_json FROM video_search_vector
            WHERE asset_id = #{assetId} AND content_key IN
            <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
            </script>
            """)
    List<VectorRow> find(@Param("assetId") long assetId, @Param("keys") List<String> keys);

    @Insert("""
            INSERT INTO video_search_vector (asset_id, content_key, embedding_json)
            VALUES (#{assetId}, #{key}, #{embeddingJson})
            ON DUPLICATE KEY UPDATE embedding_json = #{embeddingJson}
            """)
    void save(@Param("assetId") long assetId, @Param("key") String key, @Param("embeddingJson") String embeddingJson);

    record VectorRow(String contentKey, String embeddingJson) { }
}
