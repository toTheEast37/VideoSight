package videosight.local;

import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.BrokerStartup;

/** 在任何 Broker 服务启动前确认本地 HA 扩展已由 SPI 加载，避免配置遗漏后回退默认监听。 */
public final class LocalRocketMqBroker {
    private LocalRocketMqBroker() { }

    public static void main(String[] args) {
        BrokerController controller = BrokerStartup.createBrokerController(args);
        if (controller == null) throw new IllegalStateException("本地 Broker 初始化失败");
        if (controller.getMessageStore() == null
                || !(controller.getMessageStore().getHaService() instanceof LocalOnlyHaService)) {
            BrokerStartup.shutdown(controller);
            throw new IllegalStateException("本地 HA 扩展未加载，已拒绝启动；请核对开发类路径与 SPI 资源");
        }
        BrokerStartup.start(controller);
    }
}
