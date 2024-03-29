package ${packageName}.${moduleName}.controller;

import org.guess.core.web.BaseController;
import ${packageName}.${moduleName}.model.${ClassName};
import ${packageName}.${moduleName}.service.${ClassName}Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
* 
* @ClassName: ${ClassName}
* @Description: ${ClassName}Controller
* @author ${classAuthor}
* @date  ${.now}
*
*/
@Controller
@RequestMapping("/${moduleName}/${className}")
public class ${ClassName}Controller extends BaseController<${ClassName}>{

	{
		editView = "/${moduleName}/${className}/edit";
		listView = "/${moduleName}/${className}/list";
		showView = "/${moduleName}/${className}/show";
	}
	
	@Autowired
	private ${ClassName}Service ${className}Service;
}