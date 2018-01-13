package demo.service.impl;

import demo.service.INamedService;
import demo.service.IService;
import framework.annotation.MyAutowired;
import framework.annotation.MyService;

@MyService("myName")
public class NamedServiceImpl implements INamedService {

	@MyAutowired
	IService service;
	
}
