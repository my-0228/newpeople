package com.freshman.controller;

import com.freshman.common.Result;
import com.freshman.entity.*;
import com.freshman.mapper.*;
import com.freshman.rag.KnowledgeIndexer;
import com.freshman.rag.dto.IndexReport;
import com.freshman.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 后台管理控制器
 * 功能：提供系统后台管理功能，包括：
 *       1. 管理仪表盘 - 显示各模块数据统计
 *       2. 新闻管理 - 新闻的增删改查
 *       3. 用户管理 - 用户列表查看和状态切换
 *       4. 其他模块管理入口（建筑、FAQ、专业、宿舍等）
 * 负责成员：D (Team Lead)
 * 所属模块：系统管理 / 后台管理模块
 *
 * @author D (Team Lead)
 * @version 1.0
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final NewsMapper newsMapper;
    private final BuildingMapper buildingMapper;
    private final FaqMapper faqMapper;
    private final MajorMapper majorMapper;
    private final TeacherMapper teacherMapper;
    private final RegistrationStepMapper stepMapper;
    private final DormitoryMapper dormitoryMapper;
    private final CafeteriaMapper cafeteriaMapper;
    private final ClubMapper clubMapper;
    private final ActivityMapper activityMapper;
    private final ForumPostMapper postMapper;

    /** RAG 索引器：支撑 /admin/ai/reindex 知识库索引重建 */
    private final KnowledgeIndexer knowledgeIndexer;

    /** RAG 评估执行器：支撑 /admin/ai/eval */
    private final com.freshman.rag.RagEvalRunner ragEvalRunner;

    /**
     * 构造器注入所有Mapper和Service
     */
    public AdminController(UserService userService, UserMapper userMapper,
                          RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                          NewsMapper newsMapper,
                          BuildingMapper buildingMapper, FaqMapper faqMapper,
                          MajorMapper majorMapper, TeacherMapper teacherMapper,
                          RegistrationStepMapper stepMapper, DormitoryMapper dormitoryMapper,
                          CafeteriaMapper cafeteriaMapper, ClubMapper clubMapper,
                          ActivityMapper activityMapper, ForumPostMapper postMapper,
                          KnowledgeIndexer knowledgeIndexer,
                          com.freshman.rag.RagEvalRunner ragEvalRunner) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.newsMapper = newsMapper;
        this.buildingMapper = buildingMapper;
        this.faqMapper = faqMapper;
        this.majorMapper = majorMapper;
        this.teacherMapper = teacherMapper;
        this.stepMapper = stepMapper;
        this.dormitoryMapper = dormitoryMapper;
        this.cafeteriaMapper = cafeteriaMapper;
        this.clubMapper = clubMapper;
        this.activityMapper = activityMapper;
        this.postMapper = postMapper;
        this.knowledgeIndexer = knowledgeIndexer;
        this.ragEvalRunner = ragEvalRunner;
    }

    /**
     * 跑 RAG 评估集并返回 Markdown 报告
     *
     * 权限：仅 ADMIN（/admin/** 已被 SecurityConfig 限定）
     * 说明：必须加 @ResponseBody —— AdminController 是 @Controller（返回视图名），
     *       不加会去找不存在的模板而报错。
     *
     * @param generation true 时额外调用真实 LLM 判定负样本的拒答层（有成本，默认关闭）
     */
    @GetMapping(value = "/ai/eval", produces = "text/markdown;charset=UTF-8")
    @ResponseBody
    public String eval(@RequestParam(defaultValue = "false") boolean generation) {
        return com.freshman.rag.RagEvalRunner.toMarkdown(ragEvalRunner.evaluate(generation));
    }

    /**
     * 触发 RAG 知识库索引重建
     *
     * 权限：仅 ADMIN —— /admin/** 已被 SecurityConfig 限定为 hasRole("ADMIN")，
     *       因此本端点无需额外配置即可受保护。
     *
     * @param force true 时忽略 content_hash 强制重新切分与向量化（默认 false，走增量）
     * @return 重建报告（文档数/chunk 数/向量化数/跳过数/失败数/耗时）
     */
    @PostMapping("/ai/reindex")
    @ResponseBody
    public Result<IndexReport> reindex(@RequestParam(defaultValue = "false") boolean force) {
        return Result.success(knowledgeIndexer.rebuildAll(force));
    }

    /**
     * 管理后台首页/仪表盘
     * 功能：展示系统中各模块的数据统计概览，让管理员快速了解系统数据状况
     *
     * @param model Spring MVC的Model对象
     * @return 管理后台仪表盘视图
     */
    @GetMapping
    public String dashboard(Model model) {
        // 使用LinkedHashMap保持统计数据的有序性
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("用户数量", userService.count());          // 注册用户总数
        stats.put("新闻公告", newsMapper.selectCount(null));   // 新闻总数
        stats.put("校园建筑", buildingMapper.selectCount(null)); // 建筑总数
        stats.put("常见问题", faqMapper.selectCount(null));     // FAQ总数
        stats.put("专业信息", majorMapper.selectCount(null));   // 专业总数
        stats.put("教师信息", teacherMapper.selectCount(null)); // 教师总数
        stats.put("宿舍信息", dormitoryMapper.selectCount(null)); // 宿舍总数
        stats.put("食堂信息", cafeteriaMapper.selectCount(null)); // 食堂总数
        stats.put("社团信息", clubMapper.selectCount(null));     // 社团总数
        stats.put("校园活动", activityMapper.selectCount(null)); // 活动总数
        stats.put("论坛帖子", postMapper.selectCount(null));     // 帖子总数
        model.addAttribute("stats", stats);
        return "admin/dashboard";
    }

    // ==================== 新闻管理 ====================

    /**
     * 新闻管理列表页
     * 功能：展示所有新闻（包括草稿），按创建时间降序排列
     *
     * @param model Spring MVC的Model对象
     * @return 新闻管理列表视图
     */
    @GetMapping("/news")
    public String newsList(Model model) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<News>();
        wrapper.orderByDesc(News::getCreateTime);  // 按创建时间降序
        model.addAttribute("newsList", newsMapper.selectList(wrapper));
        return "admin/news";
    }

    /**
     * 新闻编辑页面
     * 功能：根据是否有ID参数决定是新增还是编辑新闻
     *
     * @param id    新闻ID（可选，有值表示编辑，无值表示新增）
     * @param model Spring MVC的Model对象
     * @return 新闻编辑视图
     */
    @GetMapping("/news/edit")
    public String newsEdit(@RequestParam(required = false) Long id, Model model) {
        // 有ID：查询已有新闻进行编辑；无ID：创建空新闻对象用于新增
        News news = id != null ? newsMapper.selectById(id) : new News();
        model.addAttribute("news", news);
        return "admin/news-edit";
    }

    /**
     * 保存新闻
     * 功能：判断是新增还是更新新闻，新增时自动设置发布时间
     *
     * @param news 前端提交的新闻实体
     * @return 重定向到新闻管理列表页
     */
    @PostMapping("/news/save")
    public String newsSave(@ModelAttribute News news) {
        if (news.getId() != null) {
            // 更新：先查询已有数据，只覆盖表单提交的字段，保留其他字段不变
            News existing = newsMapper.selectById(news.getId());
            if (existing != null) {
                existing.setTitle(news.getTitle());
                existing.setCategory(news.getCategory());
                existing.setAuthor(news.getAuthor());
                existing.setSummary(news.getSummary());
                existing.setContent(news.getContent());
                newsMapper.updateById(existing);
            }
        } else {
            // 新增：设置默认值
            news.setStatus(1);        // 默认已发布
            news.setViewCount(0);     // 初始浏览量为0
            news.setIsTop(0);         // 默认不置顶
            news.setPublishTime(java.time.LocalDateTime.now());
            newsMapper.insert(news);
        }
        return "redirect:/admin/news";
    }

    /**
     * 删除新闻
     * 功能：根据新闻ID删除指定新闻
     *
     * @param id 新闻ID（路径变量）
     * @return 重定向到新闻管理列表页
     */
    @PostMapping("/news/delete/{id}")
    public String newsDelete(@PathVariable Long id) {
        newsMapper.deleteById(id);
        return "redirect:/admin/news";
    }

    // ==================== 用户管理 ====================

    /**
     * 用户管理列表页
     * 功能：展示系统所有注册用户
     *
     * @param model Spring MVC的Model对象
     * @return 用户管理列表视图
     */
    @GetMapping("/users")
    public String userList(Model model) {
        model.addAttribute("users", userMapper.findAllWithRoles());
        // 所有可用角色（管理员在用户管理页可为用户分发角色）
        var roleWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>();
        roleWrapper.eq(Role::getStatus, 1).orderByAsc(Role::getSort);
        model.addAttribute("roles", roleMapper.selectList(roleWrapper));
        return "admin/users";
    }

    /**
     * 分发/修改用户角色（仅管理员可用，/admin/** 已由 SecurityConfig 保护）
     * 功能：将指定用户的角色更新为所选角色（先删除旧关联，再写入新关联）
     *
     * @param id     用户ID
     * @param roleId 目标角色ID（表单参数）
     * @return 重定向回用户管理列表
     */
    @PostMapping("/users/role/{id}")
    public String changeRole(@PathVariable Long id, @RequestParam Long roleId) {
        // 防呆：内置管理员(用户名admin)不允许改动角色，避免误操作失去管理能力
        User target = userService.getById(id);
        Role newRole = roleMapper.selectById(roleId);
        if (target == null || newRole == null || "admin".equals(target.getUsername())) {
            return "redirect:/admin/users?error=role";
        }
        // 删除该用户旧的角色关联
        var delWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserRole>();
        delWrapper.eq(UserRole::getUserId, id);
        userRoleMapper.delete(delWrapper);
        // 写入新的角色关联
        UserRole ur = new UserRole();
        ur.setUserId(id);
        ur.setRoleId(roleId);
        userRoleMapper.insert(ur);
        return "redirect:/admin/users?changed=" + id;
    }

    /**
     * 切换用户状态（启用/禁用）
     * 功能：管理员可以启用或禁用指定用户，被禁用的用户无法登录系统
     *
     * @param id 用户ID（路径变量）
     * @return 重定向到用户管理列表页
     */
    @GetMapping("/users/toggle/{id}")
    public String userToggle(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user != null) {
            // 状态取反：1(正常) -> 0(禁用)，0(禁用) -> 1(正常)
            user.setStatus(user.getStatus() == 1 ? 0 : 1);
            userService.updateById(user);
        }
        return "redirect:/admin/users";
    }

    // ==================== FAQ管理 ====================

    /**
     * FAQ管理列表页
     * 功能：展示所有FAQ（包括待回复和已回复），待回复问题高亮显示
     */
    @GetMapping("/faqs")
    public String faqList(Model model) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Faq>();
        wrapper.orderByAsc(Faq::getStatus)   // 待回复(0)排前面
               .orderByAsc(Faq::getSort);    // 再按排序字段
        model.addAttribute("faqs", faqMapper.selectList(wrapper));
        return "admin/faqs";
    }

    /**
     * FAQ编辑页面（新增/编辑共用）
     */
    @GetMapping("/faqs/edit")
    public String faqEdit(@RequestParam(required = false) Long id, Model model) {
        Faq faq = id != null ? faqMapper.selectById(id) : new Faq();
        model.addAttribute("faq", faq);
        return "admin/faq-edit";
    }

    /**
     * 保存FAQ（新增/编辑）
     * 功能：管理员编辑FAQ并保存，支持回复学生提交的问题
     */
    @PostMapping("/faqs/save")
    public String faqSave(@ModelAttribute Faq faq) {
        if (faq.getId() != null) {
            // 编辑已有FAQ
            Faq existing = faqMapper.selectById(faq.getId());
            if (existing != null) {
                existing.setQuestion(faq.getQuestion());
                existing.setAnswer(faq.getAnswer());
                existing.setCategory(faq.getCategory());
                existing.setStatus(faq.getStatus());
                existing.setSort(faq.getSort());
                faqMapper.updateById(existing);
            }
        } else {
            // 新增FAQ
            if (faq.getStatus() == null) faq.setStatus(1);
            if (faq.getViewCount() == null) faq.setViewCount(0);
            if (faq.getSort() == null) faq.setSort(0);
            faqMapper.insert(faq);
        }
        return "redirect:/admin/faqs";
    }

    /**
     * 删除FAQ
     */
    @PostMapping("/faqs/delete/{id}")
    public String faqDelete(@PathVariable Long id) {
        faqMapper.deleteById(id);
        return "redirect:/admin/faqs";
    }

    // ==================== 建筑管理 ====================

    /**
     * 建筑管理列表页
     * 功能：展示所有校园建筑，按排序字段升序排列
     */
    @GetMapping("/buildings")
    public String buildingList(Model model) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Building>();
        wrapper.orderByAsc(Building::getSort);
        model.addAttribute("buildings", buildingMapper.selectList(wrapper));
        return "admin/buildings";
    }

    /**
     * 建筑编辑页面（新增/编辑共用）
     */
    @GetMapping("/buildings/edit")
    public String buildingEdit(@RequestParam(required = false) Long id, Model model) {
        Building building = id != null ? buildingMapper.selectById(id) : new Building();
        model.addAttribute("building", building);
        return "admin/building-edit";
    }

    /**
     * 保存建筑（新增/编辑）
     */
    @PostMapping("/buildings/save")
    public String buildingSave(@ModelAttribute Building building) {
        if (building.getId() != null) {
            Building existing = buildingMapper.selectById(building.getId());
            if (existing != null) {
                existing.setName(building.getName());
                existing.setCategory(building.getCategory());
                existing.setDescription(building.getDescription());
                existing.setAddress(building.getAddress());
                existing.setLongitude(building.getLongitude());
                existing.setLatitude(building.getLatitude());
                existing.setFloors(building.getFloors());
                existing.setOpeningHours(building.getOpeningHours());
                existing.setImageUrl(building.getImageUrl());
                existing.setTags(building.getTags());
                existing.setSort(building.getSort());
                existing.setStatus(building.getStatus());
                buildingMapper.updateById(existing);
            }
        } else {
            if (building.getStatus() == null) building.setStatus(1);
            if (building.getSort() == null) building.setSort(0);
            buildingMapper.insert(building);
        }
        return "redirect:/admin/buildings";
    }

    /**
     * 删除建筑
     */
    @PostMapping("/buildings/delete/{id}")
    public String buildingDelete(@PathVariable Long id) {
        buildingMapper.deleteById(id);
        return "redirect:/admin/buildings";
    }

    // ==================== 专业管理 ====================

    /**
     * 专业管理列表页
     * 功能：展示所有专业信息，按学院和排序字段排列
     */
    @GetMapping("/majors")
    public String majorList(Model model) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Major>();
        wrapper.orderByAsc(Major::getCollege).orderByAsc(Major::getSort);
        model.addAttribute("majors", majorMapper.selectList(wrapper));
        return "admin/majors";
    }

    /**
     * 专业编辑页面（新增/编辑共用）
     */
    @GetMapping("/majors/edit")
    public String majorEdit(@RequestParam(required = false) Long id, Model model) {
        Major major = id != null ? majorMapper.selectById(id) : new Major();
        model.addAttribute("major", major);
        return "admin/major-edit";
    }

    /**
     * 保存专业（新增/编辑）
     */
    @PostMapping("/majors/save")
    public String majorSave(@ModelAttribute Major major) {
        if (major.getId() != null) {
            Major existing = majorMapper.selectById(major.getId());
            if (existing != null) {
                existing.setName(major.getName());
                existing.setCollege(major.getCollege());
                existing.setCode(major.getCode());
                existing.setDegree(major.getDegree());
                existing.setDuration(major.getDuration());
                existing.setDescription(major.getDescription());
                existing.setCourses(major.getCourses());
                existing.setCareerProspect(major.getCareerProspect());
                existing.setFeatures(major.getFeatures());
                existing.setSort(major.getSort());
                existing.setStatus(major.getStatus());
                majorMapper.updateById(existing);
            }
        } else {
            if (major.getStatus() == null) major.setStatus(1);
            if (major.getSort() == null) major.setSort(0);
            if (major.getDuration() == null) major.setDuration(4);
            majorMapper.insert(major);
        }
        return "redirect:/admin/majors";
    }

    /**
     * 删除专业
     */
    @PostMapping("/majors/delete/{id}")
    public String majorDelete(@PathVariable Long id) {
        majorMapper.deleteById(id);
        return "redirect:/admin/majors";
    }

    // ==================== 宿舍管理 ====================

    /**
     * 宿舍管理列表页
     * 功能：展示所有宿舍信息，按排序字段排列
     */
    @GetMapping("/dormitories")
    public String dormitoryList(Model model) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Dormitory>();
        wrapper.orderByAsc(Dormitory::getSort);
        model.addAttribute("dormitories", dormitoryMapper.selectList(wrapper));
        return "admin/dormitories";
    }

    /**
     * 宿舍编辑页面（新增/编辑共用）
     */
    @GetMapping("/dormitories/edit")
    public String dormitoryEdit(@RequestParam(required = false) Long id, Model model) {
        Dormitory dormitory = id != null ? dormitoryMapper.selectById(id) : new Dormitory();
        model.addAttribute("dormitory", dormitory);
        return "admin/dormitory-edit";
    }

    /**
     * 保存宿舍（新增/编辑）
     */
    @PostMapping("/dormitories/save")
    public String dormitorySave(@ModelAttribute Dormitory dormitory) {
        if (dormitory.getId() != null) {
            Dormitory existing = dormitoryMapper.selectById(dormitory.getId());
            if (existing != null) {
                existing.setName(dormitory.getName());
                existing.setBuildingNo(dormitory.getBuildingNo());
                existing.setType(dormitory.getType());
                existing.setRoomType(dormitory.getRoomType());
                existing.setFacilities(dormitory.getFacilities());
                existing.setDescription(dormitory.getDescription());
                existing.setFee(dormitory.getFee());
                existing.setImageUrl(dormitory.getImageUrl());
                existing.setSort(dormitory.getSort());
                existing.setStatus(dormitory.getStatus());
                dormitoryMapper.updateById(existing);
            }
        } else {
            if (dormitory.getStatus() == null) dormitory.setStatus(1);
            if (dormitory.getSort() == null) dormitory.setSort(0);
            dormitoryMapper.insert(dormitory);
        }
        return "redirect:/admin/dormitories";
    }

    /**
     * 删除宿舍
     */
    @PostMapping("/dormitories/delete/{id}")
    public String dormitoryDelete(@PathVariable Long id) {
        dormitoryMapper.deleteById(id);
        return "redirect:/admin/dormitories";
    }
}
