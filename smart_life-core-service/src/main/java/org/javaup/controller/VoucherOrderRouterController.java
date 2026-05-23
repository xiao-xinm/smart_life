package org.javaup.controller;


import jakarta.annotation.Resource;
import org.javaup.service.IVoucherOrderRouterService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券订单路由api
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/voucher-order-router")
public class VoucherOrderRouterController {

    @Resource
    private IVoucherOrderRouterService voucherOrderRouterService;
}
