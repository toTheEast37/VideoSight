package videosight.local;

import java.io.IOException;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.ha.DefaultHAService;

/** 仅供本机单 Broker 开发：保留 HA 管理接口，但不启动副本监听和传输线程。 */
public final class LocalOnlyHaService extends DefaultHAService {
    private DefaultMessageStore localStore;

    @Override
    public void init(DefaultMessageStore messageStore) throws IOException {
        // RocketMQ 5.3.4 的父类 init 只创建服务对象；Socket 的打开和绑定在 start 中。
        super.init(messageStore);
        localStore = messageStore;
    }

    @Override
    public void start() {
        if (localStore == null
                || localStore.getMessageStoreConfig().getBrokerRole() != BrokerRole.ASYNC_MASTER
                || localStore.getBrokerConfig().isEnableControllerMode()) {
            throw new IllegalStateException("本地 HA 扩展仅支持关闭 Controller 的 ASYNC_MASTER 单 Broker");
        }
        // 不调用父类 start，确保 HA Socket 从未打开；正常磁盘刷盘由 CommitLog 独立负责。
        System.out.println("本地单 Broker 模式：未启动 HA 副本监听，消息仍按配置写入本机磁盘");
    }
}
