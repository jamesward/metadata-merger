window.name = "NG_DEFER_BOOTSTRAP!";

require([
  "angular",
  "diff2html",
  "diff",
  "jquery",
  "angular-highlightjs-min",
  "highlightjs-lang-java-min",
  "css!bootstrap-css",
  "css!diff2html-css",
  "css!highlightjs-style-github-min",
  "css!app-css"
], function (angular, Diff2Html, diff) {

  angular.module("myApp", ["hljs"])
    .controller("OrgController", function ($scope, $http, $sce) {

      $http.defaults.headers.common["X-ENCRYPTED-OWNER-ID"] = window.encOwnerId;

      $scope.orgs = [];
      $scope.selectedOrgs = [];
      $scope.leftOrgData = {};
      $scope.rightOrgData = {};
      $scope.diffData = {};

      $scope.mergeAll = function() {
        var all = true;
        angular.forEach($scope.diffData.apexclasses, function(value) {
          if (!value.selectedForMerge) {
            all = false;
          }
        });
        return all;
      };

      $scope.hasData = function(data) {
        if (angular.isObject(data)) {
          return Object.keys(data).length;
        }
        else {
          return false;
        }
      };


      $scope.$watchCollection(function() {
        return [$scope.leftOrgData, $scope.rightOrgData];
      }, function() {
        if (($scope.leftOrgData.apexclasses === undefined) || ($scope.rightOrgData.apexclasses === undefined)) {
          return;
        }

        var apexclasses = {};

        function defaultApexClass() {
          return {
            left: {
              Body: null
            },
            right: {
              Body: null
            }
          };
        }

        $scope.leftOrgData.apexclasses.forEach(function(item) {
          if (apexclasses[item.Name] === undefined) {
            apexclasses[item.Name] = defaultApexClass();
          }
          apexclasses[item.Name].left = item;
        });

        $scope.rightOrgData.apexclasses.forEach(function(item) {
          if (apexclasses[item.Name] === undefined) {
            apexclasses[item.Name] = defaultApexClass();
          }
          apexclasses[item.Name].right = item;
        });

        // remove matching classes and set properties that indicate create, update, or delete
        angular.forEach(apexclasses, function(apexClass, apexClassName) {
          if ((apexClass.left.Body === null) && (apexClass.right.Body !== null)) {
            apexClass.create = true;
          }
          else if ((apexClass.left.Body !== null) && (apexClass.right.Body !== null) && (apexClass.left.Body != apexClass.right.Body)) {
            apexClass.update = true;
          }
          else if ((apexClass.left.Body !== null) && (apexClass.right.Body !== null) && (apexClass.left.Body == apexClass.right.Body)) {
            delete apexclasses[apexClassName];
          }
          else if ((apexClass.left.Body !== null) && (apexClass.right.Body === null)) {
            apexClass.delete = true;
          }
        });

        // calculate diffs
        for (var apexClassKey in apexclasses) {
          if (apexclasses.hasOwnProperty(apexClassKey)) {
            var apexClass = apexclasses[apexClassKey];

            var patchText = diff.createPatch(apexClassKey, apexClass.left.Body, apexClass.right.Body);
            patchText = "diff --git a/" + apexClassKey + " b/" + apexClassKey + "\n" + patchText;

            var diffHtml = Diff2Html.getPrettySideBySideHtmlFromDiff(patchText);

            var e = angular.element(diffHtml);

            apexclasses[apexClassKey].diff = $sce.trustAsHtml(e.html());
          }
        }

        $scope.diffData.apexclasses = apexclasses;
      });

      $scope.fetchOrgs = function() {
        $http
          .get("/orgs")
          .success(function(data, status, headers, config) {
            $scope.orgs = data;
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.fetchOrgData = function (id, callback) {
        $http
          .get("/orgs/" + id + "/metadata")
          .success(function(data, status, headers, config) {
            callback(data);
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.selectOrg = function (org) {

        var id = org.id;

        var i = $scope.selectedOrgs.indexOf(id);

        if (i >= 0) {
          // unselect
          $scope.selectedOrgs.splice(i, 1);
        }
        else if ($scope.selectedOrgs.length < 2) {
          // add selection
          $scope.selectedOrgs.push(id);
        }
        else if ($scope.selectedOrgs.length == 2) {
          // replace selection
          $scope.selectedOrgs.pop();
          $scope.selectedOrgs.push(id);
        }

        if ($scope.selectedOrgs.length == 1) {
          $scope.leftOrgData = org;
          $scope.rightOrgData = {};
          $scope.diffData = {};
          $scope.fetchOrgData($scope.selectedOrgs[0], function (data) {
            $scope.leftOrgData = data;
          });
        }
        else if ($scope.selectedOrgs.length == 2) {
          $scope.rightOrgData = org;
          $scope.fetchOrgData($scope.selectedOrgs[1], function (data) {
            $scope.rightOrgData = data;
          });
        }
      };

      $scope.getActionLabel = function(apexClass) {
        if (apexClass.create) {
          return "Create";
        }
        else if (apexClass.update) {
          return "Update";
        }
        else if (apexClass.delete) {
          return "Delete";
        }
      };

      $scope.forceMergeAll = function() {
        $scope.forceMergeSelected();
      };

      $scope.forceMergeSelected = function() {
        var apexClasses = {
          creates: {},
          updates: {},
          deletes: []
        };

        angular.forEach($scope.diffData.apexclasses, function(apexClass) {
          if (apexClass.selectedForMerge) {
            if (apexClass.create) {
              apexClasses.creates[apexClass.right.Name] = apexClass.right.Body;
            }
            else if (apexClass.update) {
              apexClasses.updates[apexClass.left.Id] = apexClass.right.Body;
            }
            else if (apexClass.delete) {
              apexClasses.deletes.push(apexClass.left.Id);
            }
          }
        });

        var url = "/orgs/" + $scope.leftOrgData.id + "/metadata";

        $http
          .post(url, { apexClasses: apexClasses })
          .success(function(data, status, headers, config) {
            $scope.diffData = {};
            $scope.fetchOrgData($scope.selectedOrgs[0], function (data) {
              $scope.leftOrgData = data;
            });
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.fetchOrgs();

    });

  angular.element(document).ready(function() {
    angular.resumeBootstrap();
  });

});