package com.vibeteaching.persistence;

import com.vibeteaching.parsing.persistence.ParseMapper;
import com.vibeteaching.parsing.persistence.ParseRepository;
import com.vibeteaching.upload.persistence.UploadRepository;
import com.vibeteaching.upload.persistence.UploadSessionMapper;
import com.vibeteaching.upload.persistence.VideoAssetMapper;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public final class RepositoryTestSupport {
    private RepositoryTestSupport() {
    }

    public static SqlSessionTemplate mapperSession(DataSource source) {
        try {
            return new SqlSessionTemplate(new PersistenceMapperConfiguration().sqlSessionFactory(source));
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建测试 Mapper 会话", failure);
        }
    }

    public static UploadRepository uploads(DataSource source) {
        SqlSessionTemplate session = mapperSession(source);
        return new UploadRepository(session.getMapper(UploadSessionMapper.class),
                session.getMapper(VideoAssetMapper.class), new DataSourceTransactionManager(source));
    }

    public static ParseRepository parses(DataSource source) {
        return new ParseRepository(mapperSession(source).getMapper(ParseMapper.class),
                new DataSourceTransactionManager(source));
    }
}
