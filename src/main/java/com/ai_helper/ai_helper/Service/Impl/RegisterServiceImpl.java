package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.RegisterService;
import com.ai_helper.ai_helper.mapper.RegisterMapper;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService {

    @Resource
    private RegisterMapper registerMapper;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 登录 token 专用：纯字符串序列化。
     *
     * 容器里同时存在 JSON 序列化的 RedisTemplate 与 Spring 自带的 StringRedisTemplate，
     * 两者都能匹配 RedisTemplate&lt;String,String&gt;，此前靠字段名兜底才选中前者，太隐晦。
     * token 的写入（这里）与读取（AuthInterceptor）必须严格一致，故显式使用 StringRedisTemplate。
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AppProperties appProperties;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Result register(UserDto user) {
        // 1. 参数校验
        if (user.getUserNumber() == null || user.getUserNumber().trim().isEmpty()) {
            return Result.error("工号不能为空");
        }
        if (user.getName() == null || user.getName().trim().isEmpty()) {
            return Result.error("姓名不能为空");
        }
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            return Result.error("邮箱不能为空");
        }
        if (user.getPassword() == null || user.getPassword().length() < 6) {
            return Result.error("密码不能少于6位");
        } else {

            try {
                //密码加密
                String encryptedPassword = passwordEncoder.encode(user.getPassword());
                user.setPassword(encryptedPassword);
                user.setCreateTime(String.valueOf(LocalDate.now()));
                registerMapper.register(user);
                return Result.success("注册成功！");
            } catch (DuplicateKeyException e) {
                // 核心：捕获唯一键冲突，返回友好提示
                if("student".equals(user.getRole())){
                    return Result.error("该学号/工号已注册，请更换账号");
                }
                else {
                    return Result.error("该工号已注册，请更换账号");
                }
            } catch (Exception e) {
                // 其他异常（如字段错误/数据库连接错误）
                log.error("注册失败：{}", e.getMessage());
                return Result.error("注册失败：" + e.getMessage());
            }
        }
    }

    @Override
    public Result login(UserDto userDto) {
        // 1. 参数校验
        if (userDto.getUserNumber() == null || userDto.getUserNumber().trim().isEmpty() && ("teacher".equals(userDto.getRole()))) {
            return Result.error("工号不能为空");
        }
        else if (userDto.getUserNumber().trim().isEmpty() && "student".equals(userDto.getRole())) {
            return Result.error("学号不能为空");
        }
        if (userDto.getPassword() == null || userDto.getPassword().trim().isEmpty()) {
            return Result.error("密码不能为空");
        }

        try {
            // 2. 查询用户是否存在
            UserDto dbUser = registerMapper.selectByUserNumber(userDto.getUserNumber());

            if (dbUser == null) {
                return Result.error("用户不存在");
            }
            if (!dbUser.getRole().equals(userDto.getRole())) {
                return Result.error("角色不匹配");
            }


            // 3. 校验密码
            if (!passwordEncoder.matches(userDto.getPassword(), dbUser.getPassword())) {
                return Result.error("密码错误");
            }

            // 4. 生成随机 token 并存入 Redis
            //    有效期由 app.auth.token-ttl-minutes 控制：一场答辩可能远超 30 分钟，
            //    token 过早失效会导致学生答到一半被踢出（改由配置驱动，不再写死 30 分钟）。
            String token = UUID.randomUUID().toString();
            stringRedisTemplate.opsForValue().set(
                    "login:token:" + token,
                    dbUser.getUserNumber(),
                    appProperties.getAuth().getTokenTtlMinutes(), TimeUnit.MINUTES
            );

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("id", dbUser.getId());
            resultMap.put("token", token);
            resultMap.put("name", dbUser.getName());
            resultMap.put("userNumber", dbUser.getUserNumber());
            resultMap.put("role", dbUser.getRole());
            resultMap.put("phoneNumber", dbUser.getPhoneNumber());
            resultMap.put("email", dbUser.getEmail());

            // 5. 返回成功结果及 token
            return Result.success(resultMap);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("登录失败：" + e.getMessage());
        }
    }


}
