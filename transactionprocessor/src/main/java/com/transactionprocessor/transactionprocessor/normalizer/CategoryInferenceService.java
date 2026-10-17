package com.transactionprocessor.transactionprocessor.normalizer;

import org.springframework.stereotype.Component;

/**
 * Merchant/type → category inference. Must never invent purchased items;
 * only assigns a coarse category label.
 */
@Component
public class CategoryInferenceService {

    public String infer(String merchant, String description, String source, String type) {
        String hay = ((merchant == null ? "" : merchant) + " " + (description == null ? "" : description)).toLowerCase();
        if (hay.contains("7 eleven") || hay.contains("7-eleven") || hay.contains("grocer") || hay.contains("mart")
                || hay.contains("supermarket") || hay.contains("taobao") || hay.contains("shopee") || hay.contains("pinduoduo")
                || hay.contains("拼多多")) return "Groceries";
        if (hay.contains("restaurant") || hay.contains("restoran") || hay.contains("coffee") || hay.contains("cafe")
                || hay.contains("mcdonald") || hay.contains("kfc") || hay.contains("grabfood") || hay.contains("food")
                || hay.contains("dining") || hay.contains("noodle") || hay.contains("cuisine") || hay.contains(" Subway ")
                || hay.contains("subway") || hay.contains("nasi") || hay.contains("prata") || hay.contains("dob")
                || hay.contains("luckin") || hay.contains("starbucks") || hay.contains("tealive") || hay.contains("mix ")) return "Dining";
        if (hay.contains("grab") && (hay.contains("justgrab") || hay.contains("klia") || hay.contains("airport")
                || hay.contains("taxi") || hay.contains("didi") || hay.contains("express"))) return "Transport";
        if (hay.contains("metro") || hay.contains("mrt") || hay.contains("bus") || hay.contains("railway")
                || hay.contains("airasia") || hay.contains("airbnb") || hay.contains("hotel") || hay.contains("marriott")) return "Transport";
        if (hay.contains("red packet") || hay.contains("红包") || hay.contains("redpacket")) return "Gifts";
        if (hay.contains("transfer") || hay.contains("duitnow") || hay.contains("top up") || hay.contains("topup")
                || hay.contains("qr") || hay.contains("fund tfr") || hay.contains("fpx") || hay.contains("repayment")
                || hay.contains("withdrawal") || hay.contains("闲鱼") || hay.contains("转账")) return "Transfer";
        if (hay.contains("cos") || hay.contains("taobao") || hay.contains("tmall") || hay.contains("clothing")
                || hay.contains("apparel") || hay.contains("裙") || hay.contains("服饰") || hay.contains("美容")
                || hay.contains("美瞳") || hay.contains("家居")) return "Shopping";
        if (hay.contains("pharmacy") || hay.contains("health") || hay.contains("医疗") || hay.contains("clinic")) return "Health";
        if (hay.contains("weixin") || hay.contains("alipay") || hay.contains("alp*")) return "Shopping";
        return "Other";
    }
}
