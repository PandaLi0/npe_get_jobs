package getjobs.service;

import getjobs.common.dto.ConfigDTO;
import getjobs.common.enums.RecruitmentPlatformEnum;
import getjobs.modules.boss.dto.JobDTO;
import getjobs.modules.boss.service.impl.BossRecruitmentServiceImpl;
import getjobs.modules.job51.service.impl.Job51RecruitmentServiceImpl;
import getjobs.modules.liepin.service.impl.LiepinRecruitmentServiceImpl;
import getjobs.modules.zhilian.service.impl.ZhiLianRecruitmentServiceImpl;
import getjobs.modules.task.quickdelivery.dto.QuickDeliveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 职位投递整合服务
 * 负责整合调度对应平台的采集、过滤、投递，完成一键投递逻辑
 * 
 * 核心流程：
 * 1. 登录检查
 * 2. 采集岗位（collectJobs）
 * 3. 过滤岗位（filterJobs）
 * 4. 执行投递（deliverJobs）
 * 5. 返回投递结果统计
 * 
 * @author getjobs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobDeliveryService {

    private final BossRecruitmentServiceImpl bossRecruitmentService;
    private final ZhiLianRecruitmentServiceImpl zhilianRecruitmentService;
    private final Job51RecruitmentServiceImpl job51RecruitmentService;
    private final LiepinRecruitmentServiceImpl liepinRecruitmentService;
    private final ConfigService configService;

    /**
     * 执行指定平台的一键投递
     * 
     * @param platform 招聘平台枚举
     * @return 投递结果统计
     */
    public QuickDeliveryResult executeQuickDelivery(RecruitmentPlatformEnum platform) {
        if (platform == null) {
            throw new IllegalArgumentException("平台参数不能为空");
        }

        log.info("========== 开始执行{}一键投递 ==========", platform.getPlatformName());
        LocalDateTime startTime = LocalDateTime.now();

        QuickDeliveryResult.QuickDeliveryResultBuilder resultBuilder = QuickDeliveryResult.builder()
                .platform(platform)
                .startTime(startTime)
                .success(false);

        try {
            // 获取平台对应的服务
            RecruitmentService recruitmentService = getRecruitmentService(platform);
            if (recruitmentService == null) {
                String errorMsg = "不支持的平台: " + platform.getPlatformName();
                log.error(errorMsg);
                return resultBuilder
                        .success(false)
                        .errorMessage(errorMsg)
                        .endTime(LocalDateTime.now())
                        .build();
            }

            // 加载平台配置
            ConfigDTO config = loadPlatformConfig(platform);
            if (config == null) {
                String errorMsg = "未找到平台配置: " + platform.getPlatformName();
                log.warn(errorMsg);
                return resultBuilder
                        .success(false)
                        .errorMessage(errorMsg)
                        .endTime(LocalDateTime.now())
                        .build();
            }

            // 步骤1: 登录检查
            log.info("步骤1: 检查{}登录状态", platform.getPlatformName());
            boolean loginSuccess = recruitmentService.login();
            if (!loginSuccess) {
                String errorMsg = platform.getPlatformName() + "登录失败，请先登录";
                log.error(errorMsg);
                return resultBuilder
                        .success(false)
                        .errorMessage(errorMsg)
                        .endTime(LocalDateTime.now())
                        .build();
            }
            log.info("✓ {}登录成功", platform.getPlatformName());

            // 步骤2: 采集岗位
            log.info("步骤2: 开始采集{}岗位", platform.getPlatformName());
            List<JobDTO> collectedJobs = new ArrayList<>();

            // 采集搜索岗位
            List<JobDTO> searchJobs = recruitmentService.collectJobs();
            if (searchJobs != null && !searchJobs.isEmpty()) {
                collectedJobs.addAll(searchJobs);
            }

            // 采集推荐岗位（如果配置了需要推荐职位）
            if (config.getRecommendJobs() != null && config.getRecommendJobs()) {
                List<JobDTO> recommendJobs = recruitmentService.collectRecommendJobs();
                if (recommendJobs != null && !recommendJobs.isEmpty()) {
                    collectedJobs.addAll(recommendJobs);
                }
            }

            int totalScanned = collectedJobs.size();
            log.info("✓ {}岗位采集完成，共采集到 {} 个岗位", platform.getPlatformName(), totalScanned);
            resultBuilder.totalScanned(totalScanned);

            if (totalScanned == 0) {
                log.warn("未采集到任何岗位，结束投递流程");
                return resultBuilder
                        .success(true)
                        .successCount(0)
                        .failedCount(0)
                        .skippedCount(0)
                        .endTime(LocalDateTime.now())
                        .remark("未采集到任何岗位")
                        .build();
            }

            // 步骤3: 过滤岗位
            log.info("步骤3: 开始过滤{}岗位", platform.getPlatformName());
            List<JobDTO> filteredJobs = recruitmentService.filterJobs(collectedJobs);
            int filteredCount = filteredJobs.size();
            int skippedCount = totalScanned - filteredCount;
            log.info("✓ {}岗位过滤完成，过滤后剩余 {} 个岗位，跳过 {} 个岗位",
                    platform.getPlatformName(), filteredCount, skippedCount);
            resultBuilder.skippedCount(skippedCount);

            if (filteredCount == 0) {
                log.warn("过滤后无可投递岗位，结束投递流程");
                return resultBuilder
                        .success(true)
                        .successCount(0)
                        .failedCount(0)
                        .endTime(LocalDateTime.now())
                        .remark("过滤后无可投递岗位")
                        .build();
            }

            // 步骤4: 执行投递
            log.info("步骤4: 开始执行{}岗位投递", platform.getPlatformName());
            int successCount = recruitmentService.deliverJobs(filteredJobs);
            int failedCount = filteredCount - successCount;
            log.info("✓ {}岗位投递完成，成功 {} 个，失败 {} 个",
                    platform.getPlatformName(), successCount, failedCount);

            // 构建投递结果
            LocalDateTime endTime = LocalDateTime.now();
            long executionTimeMillis = java.time.Duration.between(startTime, endTime).toMillis();

            QuickDeliveryResult result = resultBuilder
                    .success(true)
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .endTime(endTime)
                    .executionTimeMillis(executionTimeMillis)
                    .remark(String.format("成功投递%d个岗位", successCount))
                    .build();

            log.info("========== {}一键投递完成 ==========", platform.getPlatformName());
            log.info("投递统计: 总扫描{}个，过滤后{}个，成功{}个，失败{}个，跳过{}个，耗时{}",
                    totalScanned, filteredCount, successCount, failedCount, skippedCount,
                    result.getFormattedExecutionTime());

            return result;

        } catch (Exception e) {
            log.error("{}一键投递执行失败", platform.getPlatformName(), e);
            LocalDateTime endTime = LocalDateTime.now();
            long executionTimeMillis = java.time.Duration.between(startTime, endTime).toMillis();

            return resultBuilder
                    .success(false)
                    .errorMessage("执行异常: " + e.getMessage())
                    .endTime(endTime)
                    .executionTimeMillis(executionTimeMillis)
                    .build();
        }
    }

    /**
     * 执行所有平台的一键投递
     * 
     * @return 各平台投递结果的映射表
     */
    public Map<RecruitmentPlatformEnum, QuickDeliveryResult> executeAllPlatformsQuickDelivery() {
        log.info("========== 开始执行所有平台一键投递 ==========");
        Map<RecruitmentPlatformEnum, QuickDeliveryResult> results = new LinkedHashMap<>();

        for (RecruitmentPlatformEnum platform : RecruitmentPlatformEnum.values()) {
            try {
                QuickDeliveryResult result = executeQuickDelivery(platform);
                results.put(platform, result);
            } catch (Exception e) {
                log.error("执行{}一键投递时发生异常", platform.getPlatformName(), e);
                results.put(platform, QuickDeliveryResult.builder()
                        .platform(platform)
                        .success(false)
                        .errorMessage("执行异常: " + e.getMessage())
                        .startTime(LocalDateTime.now())
                        .endTime(LocalDateTime.now())
                        .build());
            }
        }

        // 统计汇总
        int totalSuccess = 0;
        int totalFailed = 0;
        int totalSkipped = 0;
        for (QuickDeliveryResult result : results.values()) {
            if (result.getSuccessCount() != null) {
                totalSuccess += result.getSuccessCount();
            }
            if (result.getFailedCount() != null) {
                totalFailed += result.getFailedCount();
            }
            if (result.getSkippedCount() != null) {
                totalSkipped += result.getSkippedCount();
            }
        }

        log.info("========== 所有平台一键投递完成 ==========");
        log.info("汇总统计: 成功{}个，失败{}个，跳过{}个", totalSuccess, totalFailed, totalSkipped);

        return results;
    }

    /**
     * 根据平台枚举获取对应的招聘服务
     * 
     * @param platform 招聘平台枚举
     * @return 招聘服务实现类
     */
    private RecruitmentService getRecruitmentService(RecruitmentPlatformEnum platform) {
        return switch (platform) {
            case BOSS_ZHIPIN -> bossRecruitmentService;
            case ZHILIAN_ZHAOPIN -> zhilianRecruitmentService;
            case JOB_51 -> job51RecruitmentService;
            case LIEPIN -> liepinRecruitmentService;
        };
    }

    /**
     * 加载平台配置
     * 
     * @param platform 招聘平台枚举
     * @return 配置DTO
     */
    private ConfigDTO loadPlatformConfig(RecruitmentPlatformEnum platform) {
        try {
            var configEntity = configService.loadByPlatformType(platform.getPlatformCode());
            if (configEntity == null) {
                log.warn("未找到{}的配置信息", platform.getPlatformName());
                return null;
            }

            // 使用对应服务的转换方法将ConfigEntity转为ConfigDTO
            RecruitmentService service = getRecruitmentService(platform);
            if (service instanceof AbstractRecruitmentService abstractService) {
                return abstractService.convertConfigEntityToDTO(configEntity);
            } else {
                log.warn("服务{}未继承AbstractRecruitmentService，无法转换配置",
                        platform.getPlatformName());
                return null;
            }
        } catch (Exception e) {
            log.error("加载{}配置失败", platform.getPlatformName(), e);
            return null;
        }
    }
}
