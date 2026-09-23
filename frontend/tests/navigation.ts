import { expect, type Page } from "@playwright/test";
/** 通过真实导航交互进入页面，管理员使用折叠导航配套的功能搜索。 */
export async function navigate(page:Page,label:string){
 await expect(page.getByRole("button",{name:"退出",exact:true})).toBeVisible();
 const search=page.getByRole("combobox",{name:"搜索功能",exact:true});
 if(await search.isVisible()){await search.fill(label);await page.locator(".ant-select-item-option-content").filter({hasText:new RegExp("^"+label+"$")}).click();}
 else await page.getByRole("menuitem",{name:label,exact:true}).click();
}
