package com.lrj.commerce.campaign.api;
import com.lrj.commerce.marketing.api.*;
import com.lrj.commerce.runtime.api.Inputs;
import com.lrj.commerce.kernel.DomainException;
import java.math.BigDecimal;
import java.util.List;

/** 持久化规则采用显式节点类型，不能由JSON指定任意Java类。 */
public record RuleNode(String kind,String field,String operator,String valueType,String value,List<RuleNode> children) {
    public RuleNode { if(children!=null) children=List.copyOf(children); }
    /** 先约束深度与数量，再转换成纯领域条件树。 */
    public Condition toCondition() {var result=convert(1,new int[]{0});Condition.validate(result);return result;}
    private Condition convert(int depth,int[] nodes) {
        if(depth>8||++nodes[0]>128) throw new DomainException(DomainException.Code.LIMIT_EXCEEDED,"规则节点或深度超限");
        Inputs.require(kind!=null,"规则节点类型缺失");
        if(kind.equals("COMPARE")) {
            Inputs.require(children==null||children.isEmpty(),"比较节点不能有子节点");
            Inputs.text(value,256); Inputs.require(valueType!=null&&operator!=null,"规则比较类型缺失");
            try {
                Fact fact=switch(valueType) {
                    case "TEXT" -> new Fact.Text(value);
                    case "DECIMAL" -> {Inputs.require(value.length()<=32,"数字规则长度超限");yield new Fact.Decimal(new BigDecimal(value));}
                    default -> throw new DomainException(DomainException.Code.INVALID_INPUT,"未知事实类型");
                };
                return new Condition.Compare(field,Condition.Operator.valueOf(operator),fact);
            } catch(IllegalArgumentException ex) {throw new DomainException(DomainException.Code.INVALID_INPUT,"比较规则格式无效");}
        }
        Inputs.require(field==null&&operator==null&&valueType==null&&value==null,"组合节点不能携带比较字段");
        Inputs.require(children!=null&&!children.isEmpty()&&children.size()<=16,"组合子节点数量无效");
        var converted=children.stream().map(child->child.convert(depth+1,nodes)).toList();
        return switch(kind) {
            case "ALL" -> new Condition.All(converted);
            case "ANY" -> new Condition.Any(converted);
            case "NOT" -> {Inputs.require(converted.size()==1,"NOT必须只有一个子节点");yield new Condition.Not(converted.getFirst());}
            default -> throw new DomainException(DomainException.Code.INVALID_INPUT,"未知规则节点");
        };
    }
    /** 各运营入口使用同一可信字段目录，先验证AST资源边界再检查字段。 */
    public void requireTrustedFields(){toCondition();checkFields();}
    private void checkFields(){if(kind.equals("COMPARE")){String type=MarketingAssets.TRUSTED_FIELDS.get(field);Inputs.require(type!=null&&type.equals(valueType),"规则字段或类型不在可信目录");Inputs.require("memberTags".equals(field)=="CONTAINS".equals(operator),"标签只能使用精确包含比较");}else children.forEach(RuleNode::checkFields);}
}
