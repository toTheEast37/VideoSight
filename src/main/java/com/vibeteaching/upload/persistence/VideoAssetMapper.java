package com.vibeteaching.upload.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VideoAssetMapper extends BaseMapper<VideoAssetRow> {
    List<VideoAssetRow> listRecent(@Param("limit") int limit);
}
